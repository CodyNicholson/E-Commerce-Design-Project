package com.example.mromirror.edi;

import com.example.mromirror.support.Go;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads and writes the ANSI X12 documents large customers use instead of a web
 * checkout: the 850 Purchase Order they send us and the 855 Purchase Order
 * Acknowledgment we send back.
 *
 * <p>X12 is positional. Every interchange opens with a fixed-width ISA segment
 * whose 4th character is the element separator and whose last character is
 * the segment terminator, so both are discovered rather than assumed.
 */
public final class Edi {
    private Edi() {}

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

    public static class InvalidEdiException extends Exception {
        public InvalidEdiException(String message) {
            super(message);
        }
    }

    public static final class POLine {
        public String lineNo = "";
        public int qty;
        public String uom = "";
        public double unitPrice;
        /** Our SKU (VP / VN / SK qualifier). */
        public String vendorPart = "";
        /** The customer's internal item number (BP). */
        public String buyerPart = "";
        /** The manufacturer part number (MG). */
        public String mfrPart = "";
        public String desc = "";

        public POLine() {}

        public POLine(String lineNo, int qty, String uom, double unitPrice, String vendorPart, String buyerPart, String desc) {
            this.lineNo = lineNo;
            this.qty = qty;
            this.uom = uom;
            this.unitPrice = unitPrice;
            this.vendorPart = vendorPart;
            this.buyerPart = buyerPart;
            this.desc = desc;
        }
    }

    public static final class PurchaseOrder {
        public String senderId = "";
        public String receiverId = "";
        public String controlNumber = "";
        /** 00 = original */
        public String purpose = "";
        /** SA = stand-alone */
        public String poType = "";
        public String poNumber = "";
        public String poDate = "";
        /** REF*DP, mapped to a cost center. */
        public String department = "";
        public String shipToName = "";
        /** N1*ST..*92*&lt;code&gt;, mapped to a facility. */
        public String shipToCode = "";
        public List<POLine> lines = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }

    /** Parses an X12 850 interchange. */
    public static PurchaseOrder parse850(String raw) throws InvalidEdiException {
        raw = Go.trimSpace(raw);
        if (!raw.startsWith("ISA") || raw.length() < 106) {
            throw new InvalidEdiException("document must start with a 106-character ISA segment");
        }
        String elemSep = String.valueOf(raw.charAt(3));
        String segTerm = String.valueOf(raw.charAt(105));

        PurchaseOrder po = new PurchaseOrder();
        boolean sawST = false;
        int segCount = 0;
        int cttLines = 0;
        for (String seg : raw.split(Pattern.quote(segTerm), -1)) {
            seg = Go.trimSpace(seg);
            if (seg.isEmpty()) {
                continue;
            }
            String[] e = seg.split(Pattern.quote(elemSep), -1);
            if (sawST) {
                segCount++;
            }
            switch (e[0]) {
                case "ISA" -> {
                    po.senderId = el(e, 6);
                    po.receiverId = el(e, 8);
                    po.controlNumber = el(e, 13);
                }
                case "ST" -> {
                    if (!el(e, 1).equals("850")) {
                        throw new InvalidEdiException("expected transaction set 850, got \"" + el(e, 1) + "\"");
                    }
                    sawST = true;
                    segCount = 1;
                }
                case "BEG" -> {
                    po.purpose = el(e, 1);
                    po.poType = el(e, 2);
                    po.poNumber = el(e, 3);
                    po.poDate = el(e, 5);
                }
                case "REF" -> {
                    if (el(e, 1).equals("DP")) {
                        po.department = el(e, 2);
                    }
                }
                case "N1" -> {
                    if (el(e, 1).equals("ST")) {
                        po.shipToName = el(e, 2);
                        po.shipToCode = el(e, 4);
                    }
                }
                case "PO1" -> {
                    int qty;
                    try {
                        qty = Integer.parseInt(el(e, 2));
                    } catch (NumberFormatException ex) {
                        qty = 0;
                    }
                    if (qty <= 0) {
                        throw new InvalidEdiException("PO1 line " + el(e, 1) + ": invalid quantity \"" + el(e, 2) + "\"");
                    }
                    double price;
                    try {
                        price = Double.parseDouble(el(e, 4));
                    } catch (NumberFormatException ex) {
                        price = 0;
                    }
                    POLine l = new POLine();
                    l.lineNo = el(e, 1);
                    l.qty = qty;
                    l.uom = el(e, 3);
                    l.unitPrice = price;
                    for (int i = 6; i + 1 < e.length; i += 2) {
                        switch (el(e, i)) {
                            case "VP", "VN", "SK" -> l.vendorPart = el(e, i + 1);
                            case "BP" -> l.buyerPart = el(e, i + 1);
                            case "MG" -> l.mfrPart = el(e, i + 1);
                            default -> {
                            }
                        }
                    }
                    po.lines.add(l);
                }
                case "PID" -> {
                    if (!po.lines.isEmpty()) {
                        po.lines.get(po.lines.size() - 1).desc = el(e, 5);
                    }
                }
                case "CTT" -> cttLines = Go.atoiOrZero(el(e, 1));
                case "SE" -> {
                    int n = Go.atoiOrZero(el(e, 1));
                    if (n != segCount) {
                        po.warnings.add("SE01 says " + n + " segments but the transaction set has " + segCount);
                    }
                    sawST = false;
                }
                default -> {
                }
            }
        }
        if (po.poNumber.isEmpty()) {
            throw new InvalidEdiException("missing BEG segment with a PO number");
        }
        if (po.lines.isEmpty()) {
            throw new InvalidEdiException("purchase order has no PO1 line items");
        }
        if (cttLines != 0 && cttLines != po.lines.size()) {
            po.warnings.add("CTT01 says " + cttLines + " lines but " + po.lines.size() + " PO1 segments were found");
        }
        return po;
    }

    private static String el(String[] e, int i) {
        return i < e.length ? Go.trimSpace(e[i]) : "";
    }

    /**
     * One line of an 855 acknowledgment.
     *
     * @param status IA accepted, IP accepted with price change, IB backordered, IR rejected
     */
    public record AckLine(String lineNo, int qty, String uom, double unitPrice, String vendorPart, String status) {}

    /** The envelope fields shared by outbound documents. */
    public record Interchange(String senderId, String receiverId, int control, OffsetDateTime time) {}

    private static String isa(Interchange ic) {
        return String.format(Locale.ROOT, "ISA*00*%-10s*00*%-10s*ZZ*%-15s*ZZ*%-15s*%s*%s*U*00401*%09d*0*P*>~",
                "", "", ic.senderId(), ic.receiverId(), ic.time().format(YYMMDD), ic.time().format(HHMM), ic.control());
    }

    private static String envelope(Interchange ic, String funcId, List<String> body) {
        StringBuilder b = new StringBuilder();
        b.append(isa(ic)).append('\n');
        b.append("GS*").append(funcId).append('*').append(ic.senderId()).append('*').append(ic.receiverId()).append('*')
                .append(ic.time().format(YYYYMMDD)).append('*').append(ic.time().format(HHMM)).append('*')
                .append(ic.control()).append("*X*004010~\n");
        for (String s : body) {
            b.append(s).append("~\n");
        }
        b.append("GE*1*").append(ic.control()).append("~\n");
        b.append(String.format(Locale.ROOT, "IEA*1*%09d~", ic.control()));
        return b.toString();
    }

    /** Acknowledges a purchase order line by line. */
    public static String build855(Interchange ic, String poNumber, List<AckLine> lines) {
        String ackType = "AT";
        for (AckLine l : lines) {
            if (!l.status().equals("IA")) {
                ackType = "AC";
            }
        }
        List<String> body = new ArrayList<>();
        body.add("ST*855*0001");
        body.add("BAK*00*" + ackType + "*" + poNumber + "*" + ic.time().format(YYYYMMDD));
        for (AckLine l : lines) {
            body.add("PO1*" + l.lineNo() + "*" + l.qty() + "*" + l.uom() + "*" + Go.fixed(l.unitPrice(), 2) + "**VP*" + l.vendorPart());
            body.add("ACK*" + l.status() + "*" + l.qty() + "*" + l.uom());
        }
        body.add("CTT*" + lines.size());
        body.add("SE*" + (body.size() + 1) + "*0001");
        return envelope(ic, "PR", body);
    }

    /**
     * Writes a purchase order. The mirror uses it to play the customer's ERP so
     * the inbound side can be exercised end to end.
     */
    public static String build850(Interchange ic, PurchaseOrder po) {
        List<String> body = new ArrayList<>();
        body.add("ST*850*0001");
        body.add("BEG*00*SA*" + po.poNumber + "**" + ic.time().format(YYYYMMDD));
        if (!po.department.isEmpty()) {
            body.add("REF*DP*" + po.department);
        }
        if (!po.shipToCode.isEmpty()) {
            body.add("N1*ST*" + po.shipToName + "*92*" + po.shipToCode);
        }
        for (POLine l : po.lines) {
            String seg = "PO1*" + l.lineNo + "*" + l.qty + "*" + l.uom + "*" + Go.fixed(l.unitPrice, 2) + "**VP*" + l.vendorPart;
            if (!l.buyerPart.isEmpty()) {
                seg += "*BP*" + l.buyerPart;
            }
            body.add(seg);
            if (!l.desc.isEmpty()) {
                body.add("PID*F****" + l.desc.replace("*", " "));
            }
        }
        body.add("CTT*" + po.lines.size());
        body.add("SE*" + (body.size() + 1) + "*0001");
        return envelope(ic, "PO", body);
    }
}
