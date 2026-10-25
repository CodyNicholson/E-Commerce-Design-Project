package com.example.mromirror.cxml;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CxmlTest {
    static final OffsetDateTime TS = OffsetDateTime.of(2026, 10, 23, 14, 30, 0, 0, ZoneOffset.UTC);

    @Test
    void parseSampleSetupRequest() throws Exception {
        String doc = Cxml.sampleSetupRequest("AN01000123-MFP", "s3cret", "SAP Ariba", "dana@example.com", "COOKIE-1",
                "https://erp.example/return", TS);
        var r = Cxml.parseSetupRequest(doc);
        assertEquals("AN01000123-MFP", r.fromIdentity());
        assertEquals("s3cret", r.senderSecret());
        assertEquals("COOKIE-1", r.buyerCookie());
        assertEquals("https://erp.example/return", r.returnUrl());
        assertEquals("dana@example.com", r.extrinsics().get("UserEmail"));
        assertEquals("create", r.operation());
        assertEquals("SAP Ariba", r.userAgent());
        assertEquals(TS.toEpochSecond() + ".setup@erp.example", r.payloadId());
    }

    @Test
    void parseRejectsOtherRequests() {
        assertThrows(Cxml.InvalidCxmlException.class, () -> Cxml.parseSetupRequest("<cXML><Request><OrderRequest/></Request></cXML>"));
        assertThrows(Cxml.InvalidCxmlException.class, () -> Cxml.parseSetupRequest("not xml"));
    }

    @Test
    void parseNeverExpandsExternalEntities() {
        String doc = """
                <?xml version="1.0"?>
                <!DOCTYPE cXML [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <cXML><Header><From><Credential><Identity>&xxe;</Identity></Credential></From></Header>
                <Request><PunchOutSetupRequest><BuyerCookie>c</BuyerCookie><BrowserFormPost><URL>u</URL></BrowserFormPost>
                </PunchOutSetupRequest></Request></cXML>""";
        var e = assertThrows(Cxml.InvalidCxmlException.class, () -> Cxml.parseSetupRequest(doc));
        assertTrue(!e.getMessage().contains("root:"), e.getMessage());
    }

    @Test
    void orderMessageIsWellFormedAndEscaped() {
        String doc = Cxml.buildOrderMessage("p1", TS, "AN01000123-MFP", "COOKIE-1", List.of(
                new Cxml.Item("4KW71", 2, 539, "Motor, 1 HP <TEFC> & more", "EA", "26101112", "", ""),
                new Cxml.Item("HC019", 3, 10.13, "Cap screw", "PK", "", "", "")));
        assertTrue(doc.contains("&lt;TEFC&gt; &amp; more"), "description not escaped");
        assertTrue(doc.contains("<Money currency=\"USD\">1108.39</Money>"), "wrong total:\n" + doc);
        assertTrue(doc.contains("timestamp=\"2026-10-23T14:30:00Z\""), doc);
        assertDoesNotThrow(() -> {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.newDocumentBuilder().parse(new InputSource(new StringReader(doc)));
        }, "not well-formed");
    }

    @Test
    void setupResponse() {
        String doc = Cxml.buildSetupResponse("p", TS, 200, "OK", "http://localhost/punchout/abc?x=1&y=2");
        assertTrue(doc.contains("<URL>http://localhost/punchout/abc?x=1&amp;y=2</URL>"), doc);
    }

    @Test
    void escapesLikeGo() {
        assertEquals("&#34;a&#39; &amp; b&#xA;", Cxml.esc("\"a' & b\n"));
    }
}
