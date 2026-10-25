package com.example.mromirror.edi;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdiTest {
    static final Edi.Interchange IC = new Edi.Interchange("ATLASAERO", "MROMIRROR", 42,
            OffsetDateTime.of(2026, 10, 23, 14, 30, 0, 0, ZoneOffset.UTC));

    static Edi.PurchaseOrder samplePO() {
        Edi.PurchaseOrder po = new Edi.PurchaseOrder();
        po.poNumber = "ATL-60021";
        po.department = "7100";
        po.shipToName = "Fort Worth Assembly";
        po.shipToCode = "FTW";
        po.lines.add(new Edi.POLine("1", 20, "EA", 12.95, "6BRG1", "ATL-BRG-6205", "Bearing 6205-2RS"));
        po.lines.add(new Edi.POLine("2", 4, "PK", 30.00, "HC030", "", ""));
        return po;
    }

    @Test
    void roundTrip850() throws Exception {
        String doc = Edi.build850(IC, samplePO());
        assertTrue(doc.startsWith("ISA*00*"), doc);
        assertEquals(106, doc.indexOf('~') + 1, "ISA must be 106 chars");
        var po = Edi.parse850(doc);
        assertEquals("ATLASAERO", po.senderId);
        assertEquals("ATL-60021", po.poNumber);
        assertEquals("FTW", po.shipToCode);
        assertEquals("7100", po.department);
        assertEquals(2, po.lines.size());
        assertEquals("6BRG1", po.lines.get(0).vendorPart);
        assertEquals("ATL-BRG-6205", po.lines.get(0).buyerPart);
        assertEquals("Bearing 6205-2RS", po.lines.get(0).desc);
        assertEquals(4, po.lines.get(1).qty);
        assertEquals(30, po.lines.get(1).unitPrice);
        assertTrue(po.warnings.isEmpty(), po.warnings.toString());
    }

    @Test
    void parseDetectsSeparatorsAndCountMismatch() throws Exception {
        String doc = Edi.build850(IC, samplePO()).replace("*", "|").replaceFirst("CTT\\|2", "CTT|5");
        var po = Edi.parse850(doc);
        assertEquals(2, po.lines.size());
        assertEquals(1, po.warnings.size(), "expected a CTT warning");
    }

    @Test
    void parseRejectsGarbage() {
        assertThrows(Edi.InvalidEdiException.class, () -> Edi.parse850("hello"));
        String bad = Edi.build850(IC, samplePO()).replaceFirst("ST\\*850", "ST*810");
        assertThrows(Edi.InvalidEdiException.class, () -> Edi.parse850(bad), "non-850 transaction set");
    }

    @Test
    void build855() {
        String doc = Edi.build855(IC, "ATL-60021", List.of(
                new Edi.AckLine("1", 20, "EA", 12.95, "6BRG1", "IA"),
                new Edi.AckLine("2", 4, "PK", 28.11, "HC030", "IP")));
        for (String want : List.of("ST*855*0001", "BAK*00*AC*ATL-60021*20261023", "ACK*IP*4*PK", "CTT*2", "SE*8*0001")) {
            assertTrue(doc.contains(want), "855 missing " + want + ":\n" + doc);
        }
    }
}
