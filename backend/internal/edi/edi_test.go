package edi

import (
	"strings"
	"testing"
	"time"
)

var ic = Interchange{SenderID: "ATLASAERO", ReceiverID: "MROMIRROR", Control: 42, Time: time.Date(2026, 10, 23, 14, 30, 0, 0, time.UTC)}

func samplePO() PurchaseOrder {
	return PurchaseOrder{
		PONumber: "ATL-60021", Department: "7100", ShipToName: "Fort Worth Assembly", ShipToCode: "FTW",
		Lines: []POLine{
			{LineNo: "1", Qty: 20, UOM: "EA", UnitPrice: 12.95, VendorPart: "6BRG1", BuyerPart: "ATL-BRG-6205", Desc: "Bearing 6205-2RS"},
			{LineNo: "2", Qty: 4, UOM: "PK", UnitPrice: 30.00, VendorPart: "HC030"},
		},
	}
}

func TestRoundTrip850(t *testing.T) {
	doc := Build850(ic, samplePO())
	if !strings.HasPrefix(doc, "ISA*00*") {
		t.Fatalf("bad envelope: %s", doc)
	}
	if isaLen := strings.Index(doc, "~") + 1; isaLen != 106 {
		t.Fatalf("ISA must be 106 chars, got %d", isaLen)
	}
	po, err := Parse850(doc)
	if err != nil {
		t.Fatal(err)
	}
	if po.SenderID != "ATLASAERO" || po.PONumber != "ATL-60021" || po.ShipToCode != "FTW" || po.Department != "7100" {
		t.Fatalf("header mismatch: %+v", po)
	}
	if len(po.Lines) != 2 || po.Lines[0].VendorPart != "6BRG1" || po.Lines[0].BuyerPart != "ATL-BRG-6205" || po.Lines[0].Desc != "Bearing 6205-2RS" {
		t.Fatalf("lines mismatch: %+v", po.Lines)
	}
	if po.Lines[1].Qty != 4 || po.Lines[1].UnitPrice != 30 {
		t.Fatalf("line 2 mismatch: %+v", po.Lines[1])
	}
	if len(po.Warnings) != 0 {
		t.Fatalf("unexpected warnings: %v", po.Warnings)
	}
}

func TestParseDetectsSeparatorsAndCountMismatch(t *testing.T) {
	doc := Build850(ic, samplePO())
	doc = strings.ReplaceAll(doc, "*", "|")
	doc = strings.Replace(doc, "CTT|2", "CTT|5", 1)
	po, err := Parse850(doc)
	if err != nil {
		t.Fatal(err)
	}
	if len(po.Lines) != 2 || len(po.Warnings) != 1 {
		t.Fatalf("expected 2 lines and a CTT warning, got %+v", po)
	}
}

func TestParseRejectsGarbage(t *testing.T) {
	if _, err := Parse850("hello"); err == nil {
		t.Fatal("expected error")
	}
	bad := strings.Replace(Build850(ic, samplePO()), "ST*850", "ST*810", 1)
	if _, err := Parse850(bad); err == nil {
		t.Fatal("expected error for non-850 transaction set")
	}
}

func TestBuild855(t *testing.T) {
	doc := Build855(ic, "ATL-60021", []AckLine{
		{LineNo: "1", Qty: 20, UOM: "EA", UnitPrice: 12.95, VendorPart: "6BRG1", Status: "IA"},
		{LineNo: "2", Qty: 4, UOM: "PK", UnitPrice: 28.11, VendorPart: "HC030", Status: "IP"},
	})
	for _, want := range []string{"ST*855*0001", "BAK*00*AC*ATL-60021*20261023", "ACK*IP*4*PK", "CTT*2", "SE*8*0001"} {
		if !strings.Contains(doc, want) {
			t.Fatalf("855 missing %q:\n%s", want, doc)
		}
	}
}
