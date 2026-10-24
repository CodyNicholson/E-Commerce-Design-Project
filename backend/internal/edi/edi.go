// Package edi reads and writes the ANSI X12 documents large customers use
// instead of a web checkout: the 850 Purchase Order they send us and the 855
// Purchase Order Acknowledgment we send back.
//
// X12 is positional. Every interchange opens with a fixed-width ISA segment
// whose 4th character is the element separator and whose last character is
// the segment terminator, so both are discovered rather than assumed.
package edi

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

type POLine struct {
	LineNo     string  `json:"lineNo"`
	Qty        int     `json:"qty"`
	UOM        string  `json:"uom"`
	UnitPrice  float64 `json:"unitPrice"`
	VendorPart string  `json:"vendorPart"` // our SKU (VP / VN / SK qualifier)
	BuyerPart  string  `json:"buyerPart"`  // customer's internal item number (BP)
	MfrPart    string  `json:"mfrPart"`    // manufacturer part number (MG)
	Desc       string  `json:"desc"`
}

type PurchaseOrder struct {
	SenderID      string   `json:"senderId"`
	ReceiverID    string   `json:"receiverId"`
	ControlNumber string   `json:"controlNumber"`
	Purpose       string   `json:"purpose"` // 00 = original
	POType        string   `json:"poType"`  // SA = stand-alone
	PONumber      string   `json:"poNumber"`
	PODate        string   `json:"poDate"`
	Department    string   `json:"department"` // REF*DP, mapped to a cost center
	ShipToName    string   `json:"shipToName"`
	ShipToCode    string   `json:"shipToCode"` // N1*ST..*92*<code>, mapped to a facility
	Lines         []POLine `json:"lines"`
	Warnings      []string `json:"warnings"`
}

// Parse850 parses an X12 850 interchange.
func Parse850(raw string) (*PurchaseOrder, error) {
	raw = strings.TrimSpace(raw)
	if !strings.HasPrefix(raw, "ISA") || len(raw) < 106 {
		return nil, errors.New("document must start with a 106-character ISA segment")
	}
	elemSep := string(raw[3])
	segTerm := string(raw[105])

	po := &PurchaseOrder{Warnings: []string{}}
	var sawST bool
	var segCount, cttLines int
	for _, seg := range strings.Split(raw, segTerm) {
		seg = strings.TrimSpace(seg)
		if seg == "" {
			continue
		}
		e := strings.Split(seg, elemSep)
		el := func(i int) string {
			if i < len(e) {
				return strings.TrimSpace(e[i])
			}
			return ""
		}
		if sawST {
			segCount++
		}
		switch e[0] {
		case "ISA":
			po.SenderID, po.ReceiverID, po.ControlNumber = el(6), el(8), el(13)
		case "ST":
			if el(1) != "850" {
				return nil, fmt.Errorf("expected transaction set 850, got %q", el(1))
			}
			sawST = true
			segCount = 1
		case "BEG":
			po.Purpose, po.POType, po.PONumber, po.PODate = el(1), el(2), el(3), el(5)
		case "REF":
			if el(1) == "DP" {
				po.Department = el(2)
			}
		case "N1":
			if el(1) == "ST" {
				po.ShipToName, po.ShipToCode = el(2), el(4)
			}
		case "PO1":
			qty, err := strconv.Atoi(el(2))
			if err != nil || qty <= 0 {
				return nil, fmt.Errorf("PO1 line %s: invalid quantity %q", el(1), el(2))
			}
			price, _ := strconv.ParseFloat(el(4), 64)
			l := POLine{LineNo: el(1), Qty: qty, UOM: el(3), UnitPrice: price}
			for i := 6; i+1 < len(e); i += 2 {
				switch el(i) {
				case "VP", "VN", "SK":
					l.VendorPart = el(i + 1)
				case "BP":
					l.BuyerPart = el(i + 1)
				case "MG":
					l.MfrPart = el(i + 1)
				}
			}
			po.Lines = append(po.Lines, l)
		case "PID":
			if n := len(po.Lines); n > 0 {
				po.Lines[n-1].Desc = el(5)
			}
		case "CTT":
			cttLines, _ = strconv.Atoi(el(1))
		case "SE":
			if n, _ := strconv.Atoi(el(1)); n != segCount {
				po.Warnings = append(po.Warnings, fmt.Sprintf("SE01 says %d segments but the transaction set has %d", n, segCount))
			}
			sawST = false
		}
	}
	if po.PONumber == "" {
		return nil, errors.New("missing BEG segment with a PO number")
	}
	if len(po.Lines) == 0 {
		return nil, errors.New("purchase order has no PO1 line items")
	}
	if cttLines != 0 && cttLines != len(po.Lines) {
		po.Warnings = append(po.Warnings, fmt.Sprintf("CTT01 says %d lines but %d PO1 segments were found", cttLines, len(po.Lines)))
	}
	return po, nil
}

// AckLine is one line of an 855 acknowledgment.
type AckLine struct {
	LineNo     string
	Qty        int
	UOM        string
	UnitPrice  float64
	VendorPart string
	// Status: IA accepted, IP accepted with price change, IB backordered, IR rejected.
	Status string
}

// Interchange carries the envelope fields shared by outbound documents.
type Interchange struct {
	SenderID   string
	ReceiverID string
	Control    int
	Time       time.Time
}

func isa(ic Interchange) string {
	return fmt.Sprintf("ISA*00*%-10s*00*%-10s*ZZ*%-15s*ZZ*%-15s*%s*%s*U*00401*%09d*0*P*>~",
		"", "", ic.SenderID, ic.ReceiverID, ic.Time.Format("060102"), ic.Time.Format("1504"), ic.Control)
}

func envelope(ic Interchange, funcID string, body []string) string {
	var b strings.Builder
	b.WriteString(isa(ic) + "\n")
	fmt.Fprintf(&b, "GS*%s*%s*%s*%s*%s*%d*X*004010~\n", funcID, ic.SenderID, ic.ReceiverID, ic.Time.Format("20060102"), ic.Time.Format("1504"), ic.Control)
	for _, s := range body {
		b.WriteString(s + "~\n")
	}
	fmt.Fprintf(&b, "GE*1*%d~\n", ic.Control)
	fmt.Fprintf(&b, "IEA*1*%09d~", ic.Control)
	return b.String()
}

// Build855 acknowledges a purchase order line by line.
func Build855(ic Interchange, poNumber string, lines []AckLine) string {
	ackType := "AT"
	for _, l := range lines {
		if l.Status != "IA" {
			ackType = "AC"
		}
	}
	body := []string{"ST*855*0001", fmt.Sprintf("BAK*00*%s*%s*%s", ackType, poNumber, ic.Time.Format("20060102"))}
	for _, l := range lines {
		body = append(body,
			fmt.Sprintf("PO1*%s*%d*%s*%.2f**VP*%s", l.LineNo, l.Qty, l.UOM, l.UnitPrice, l.VendorPart),
			fmt.Sprintf("ACK*%s*%d*%s", l.Status, l.Qty, l.UOM))
	}
	body = append(body, fmt.Sprintf("CTT*%d", len(lines)))
	body = append(body, fmt.Sprintf("SE*%d*0001", len(body)+1))
	return envelope(ic, "PR", body)
}

// Build850 writes a purchase order. The mirror uses it to play the customer's
// ERP so the inbound side can be exercised end to end.
func Build850(ic Interchange, po PurchaseOrder) string {
	body := []string{"ST*850*0001", fmt.Sprintf("BEG*00*SA*%s**%s", po.PONumber, ic.Time.Format("20060102"))}
	if po.Department != "" {
		body = append(body, "REF*DP*"+po.Department)
	}
	if po.ShipToCode != "" {
		body = append(body, fmt.Sprintf("N1*ST*%s*92*%s", po.ShipToName, po.ShipToCode))
	}
	for _, l := range po.Lines {
		seg := fmt.Sprintf("PO1*%s*%d*%s*%.2f**VP*%s", l.LineNo, l.Qty, l.UOM, l.UnitPrice, l.VendorPart)
		if l.BuyerPart != "" {
			seg += "*BP*" + l.BuyerPart
		}
		body = append(body, seg)
		if l.Desc != "" {
			body = append(body, "PID*F****"+strings.ReplaceAll(l.Desc, "*", " "))
		}
	}
	body = append(body, fmt.Sprintf("CTT*%d", len(po.Lines)))
	body = append(body, fmt.Sprintf("SE*%d*0001", len(body)+1))
	return envelope(ic, "PO", body)
}
