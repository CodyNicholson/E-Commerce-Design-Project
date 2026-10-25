package com.example.mromirror.cxml;

import com.example.mromirror.support.Go;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The PunchOut half of eProcurement.
 *
 * <p>A buyer working inside SAP Ariba, Coupa or Oracle clicks our catalog. Their
 * ERP posts a PunchOutSetupRequest (with a shared secret), we answer with a
 * StartPage URL, the buyer shops our site with their contract pricing, and
 * instead of checking out we post the cart back to the ERP as a
 * PunchOutOrderMessage. The ERP runs its own approvals and later sends the
 * real order (usually EDI 850 or cXML OrderRequest).
 */
public final class Cxml {
    private Cxml() {}

    /** RFC 3339 without fractional seconds, like Go's time.RFC3339. */
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    public static class InvalidCxmlException extends Exception {
        public InvalidCxmlException(String message) {
            super(message);
        }
    }

    public record SetupRequest(String payloadId, String fromIdentity, String senderSecret, String userAgent,
                               String operation, String buyerCookie, String returnUrl, Map<String, String> extrinsics) {}

    public static SetupRequest parseSetupRequest(String raw) throws InvalidCxmlException {
        Element root;
        try {
            root = parse(raw).getDocumentElement();
        } catch (Exception e) {
            throw new InvalidCxmlException("invalid cXML: " + e.getMessage());
        }
        if (!"cXML".equals(root.getLocalName())) {
            throw new InvalidCxmlException("invalid cXML: expected element type <cXML> but have <" + root.getLocalName() + ">");
        }
        Element header = child(root, "Header");
        Element setup = child(child(root, "Request"), "PunchOutSetupRequest");
        if (setup == null) {
            throw new InvalidCxmlException("cXML does not contain a PunchOutSetupRequest");
        }
        Element sender = child(header, "Sender");
        Map<String, String> extrinsics = new LinkedHashMap<>();
        for (Element e : children(setup, "Extrinsic")) {
            extrinsics.put(e.getAttribute("name"), Go.trimSpace(text(e)));
        }
        SetupRequest req = new SetupRequest(
                root.getAttribute("payloadID"),
                Go.trimSpace(text(child(child(child(header, "From"), "Credential"), "Identity"))),
                Go.trimSpace(text(child(child(sender, "Credential"), "SharedSecret"))),
                Go.trimSpace(text(child(sender, "UserAgent"))),
                setup.getAttribute("operation"),
                Go.trimSpace(text(child(setup, "BuyerCookie"))),
                Go.trimSpace(text(child(child(setup, "BrowserFormPost"), "URL"))),
                extrinsics);
        if (req.fromIdentity().isEmpty() || req.buyerCookie().isEmpty() || req.returnUrl().isEmpty()) {
            throw new InvalidCxmlException("PunchOutSetupRequest needs From identity, BuyerCookie and BrowserFormPost URL");
        }
        return req;
    }

    /**
     * Parses without ever fetching the cXML DTD the DOCTYPE points at, and
     * without expanding external entities.
     */
    private static Document parse(String raw) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(new DefaultHandler()); // fatal errors throw; nothing is printed
        return b.parse(new InputSource(new StringReader(raw)));
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    /** The element's own character data, ignoring nested elements. */
    private static String text(Element e) {
        if (e == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                b.append(n.getNodeValue());
            }
        }
        return b.toString();
    }

    private static String header(String payloadId, OffsetDateTime ts) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE cXML SYSTEM \"http://xml.cxml.org/schemas/cXML/1.2.014/cXML.dtd\">\n"
                + "<cXML payloadID=\"" + esc(payloadId) + "\" timestamp=\"" + ts.format(RFC3339) + "\" xml:lang=\"en-US\">";
    }

    /** Answers a setup request with the URL the buyer's browser opens. */
    public static String buildSetupResponse(String payloadId, OffsetDateTime ts, int code, String text, String startUrl) {
        StringBuilder b = new StringBuilder(header(payloadId, ts));
        b.append("\n  <Response>\n    <Status code=\"").append(code).append("\" text=\"").append(esc(text)).append("\"/>");
        if (!startUrl.isEmpty()) {
            b.append("\n    <PunchOutSetupResponse>\n      <StartPage><URL>").append(esc(startUrl))
                    .append("</URL></StartPage>\n    </PunchOutSetupResponse>");
        }
        b.append("\n  </Response>\n</cXML>");
        return b.toString();
    }

    public record Item(String supplierPartId, int quantity, double unitPrice, String description, String uom,
                       String unspsc, String mfrPartId, String mfrName) {}

    /** The cart handed back to the ERP for its own approval workflow. */
    public static String buildOrderMessage(String payloadId, OffsetDateTime ts, String fromIdentity, String buyerCookie,
                                           List<Item> items) {
        double total = 0;
        for (Item it : items) {
            total += it.quantity() * it.unitPrice();
        }
        StringBuilder b = new StringBuilder(header(payloadId, ts));
        b.append("\n  <Header>\n"
                + "    <From><Credential domain=\"DUNS\"><Identity>MROMIRROR</Identity></Credential></From>\n"
                + "    <To><Credential domain=\"NetworkID\"><Identity>").append(esc(fromIdentity)).append("</Identity></Credential></To>\n"
                + "    <Sender><Credential domain=\"DUNS\"><Identity>MROMIRROR</Identity></Credential><UserAgent>MRO Mirror PunchOut</UserAgent></Sender>\n"
                + "  </Header>\n"
                + "  <Message>\n"
                + "    <PunchOutOrderMessage>\n"
                + "      <BuyerCookie>").append(esc(buyerCookie)).append("</BuyerCookie>\n"
                + "      <PunchOutOrderMessageHeader operationAllowed=\"edit\">\n"
                + "        <Total><Money currency=\"USD\">").append(Go.fixed(total, 2)).append("</Money></Total>\n"
                + "      </PunchOutOrderMessageHeader>");
        for (Item it : items) {
            b.append("\n      <ItemIn quantity=\"").append(it.quantity()).append("\">\n"
                    + "        <ItemID><SupplierPartID>").append(esc(it.supplierPartId())).append("</SupplierPartID></ItemID>\n"
                    + "        <ItemDetail>\n"
                    + "          <UnitPrice><Money currency=\"USD\">").append(Go.fixed(it.unitPrice(), 2)).append("</Money></UnitPrice>\n"
                    + "          <Description xml:lang=\"en\">").append(esc(it.description())).append("</Description>\n"
                    + "          <UnitOfMeasure>").append(esc(it.uom())).append("</UnitOfMeasure>\n"
                    + "          <Classification domain=\"UNSPSC\">").append(esc(it.unspsc())).append("</Classification>\n"
                    + "          <ManufacturerPartID>").append(esc(it.mfrPartId())).append("</ManufacturerPartID>\n"
                    + "          <ManufacturerName>").append(esc(it.mfrName())).append("</ManufacturerName>\n"
                    + "        </ItemDetail>\n"
                    + "      </ItemIn>");
        }
        b.append("\n    </PunchOutOrderMessage>\n  </Message>\n</cXML>");
        return b.toString();
    }

    /** Escapes text and attribute values the way Go's xml.EscapeText does. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '"' -> b.append("&#34;");
                case '\'' -> b.append("&#39;");
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '\t' -> b.append("&#x9;");
                case '\n' -> b.append("&#xA;");
                case '\r' -> b.append("&#xD;");
                default -> {
                    if (isXmlChar(cp)) {
                        b.appendCodePoint(cp);
                    } else {
                        b.append('�');
                    }
                }
            }
        }
        return b.toString();
    }

    private static boolean isXmlChar(int cp) {
        return cp == 0x09 || cp == 0x0A || cp == 0x0D
                || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    /** What an ERP would post; the simulator pre-fills it. */
    public static String sampleSetupRequest(String identity, String secret, String erp, String email, String cookie,
                                            String returnUrl, OffsetDateTime ts) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE cXML SYSTEM \"http://xml.cxml.org/schemas/cXML/1.2.014/cXML.dtd\">\n"
                + "<cXML payloadID=\"" + ts.toEpochSecond() + ".setup@erp.example\" timestamp=\"" + ts.format(RFC3339) + "\" xml:lang=\"en-US\">\n"
                + "  <Header>\n"
                + "    <From><Credential domain=\"NetworkID\"><Identity>" + esc(identity) + "</Identity></Credential></From>\n"
                + "    <To><Credential domain=\"DUNS\"><Identity>MROMIRROR</Identity></Credential></To>\n"
                + "    <Sender>\n"
                + "      <Credential domain=\"NetworkID\"><Identity>" + esc(identity) + "</Identity><SharedSecret>" + esc(secret) + "</SharedSecret></Credential>\n"
                + "      <UserAgent>" + esc(erp) + "</UserAgent>\n"
                + "    </Sender>\n"
                + "  </Header>\n"
                + "  <Request deploymentMode=\"test\">\n"
                + "    <PunchOutSetupRequest operation=\"create\">\n"
                + "      <BuyerCookie>" + esc(cookie) + "</BuyerCookie>\n"
                + "      <Extrinsic name=\"UserEmail\">" + esc(email) + "</Extrinsic>\n"
                + "      <BrowserFormPost><URL>" + esc(returnUrl) + "</URL></BrowserFormPost>\n"
                + "    </PunchOutSetupRequest>\n"
                + "  </Request>\n"
                + "</cXML>";
    }
}
