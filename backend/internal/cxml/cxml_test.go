package cxml

import (
	"encoding/xml"
	"io"
	"strings"
	"testing"
	"time"
)

var ts = time.Date(2026, 10, 23, 14, 30, 0, 0, time.UTC)

func TestParseSampleSetupRequest(t *testing.T) {
	doc := SampleSetupRequest("AN01000123-MFP", "s3cret", "SAP Ariba", "dana@example.com", "COOKIE-1", "https://erp.example/return", ts)
	r, err := ParseSetupRequest([]byte(doc))
	if err != nil {
		t.Fatal(err)
	}
	if r.FromIdentity != "AN01000123-MFP" || r.SenderSecret != "s3cret" || r.BuyerCookie != "COOKIE-1" ||
		r.ReturnURL != "https://erp.example/return" || r.Extrinsics["UserEmail"] != "dana@example.com" || r.Operation != "create" {
		t.Fatalf("parsed %+v", r)
	}
}

func TestParseRejectsOtherRequests(t *testing.T) {
	if _, err := ParseSetupRequest([]byte(`<cXML><Request><OrderRequest/></Request></cXML>`)); err == nil {
		t.Fatal("expected error")
	}
	if _, err := ParseSetupRequest([]byte(`not xml`)); err == nil {
		t.Fatal("expected error")
	}
}

func TestOrderMessageIsWellFormedAndEscaped(t *testing.T) {
	doc := BuildOrderMessage("p1", ts, "AN01000123-MFP", "COOKIE-1", []Item{
		{SupplierPartID: "4KW71", Quantity: 2, UnitPrice: 539, Description: "Motor, 1 HP <TEFC> & more", UOM: "EA", UNSPSC: "26101112"},
		{SupplierPartID: "HC019", Quantity: 3, UnitPrice: 10.13, Description: "Cap screw", UOM: "PK"},
	})
	if !strings.Contains(doc, "&lt;TEFC&gt; &amp; more") {
		t.Fatal("description not escaped")
	}
	if !strings.Contains(doc, `<Money currency="USD">1108.39</Money>`) {
		t.Fatalf("wrong total:\n%s", doc)
	}
	// The standard decoder accepts the DOCTYPE directive, so walking every token checks well-formedness.
	d := xml.NewDecoder(strings.NewReader(doc))
	for {
		_, err := d.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatalf("not well-formed: %v", err)
		}
	}
}

func TestSetupResponse(t *testing.T) {
	doc := BuildSetupResponse("p", ts, 200, "OK", "http://localhost/punchout/abc?x=1&y=2")
	if !strings.Contains(doc, "<URL>http://localhost/punchout/abc?x=1&amp;y=2</URL>") {
		t.Fatalf("got %s", doc)
	}
}
