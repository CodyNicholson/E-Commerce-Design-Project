// Package cxml implements the PunchOut half of eProcurement.
//
// A buyer working inside SAP Ariba, Coupa or Oracle clicks our catalog. Their
// ERP posts a PunchOutSetupRequest (with a shared secret), we answer with a
// StartPage URL, the buyer shops our site with their contract pricing, and
// instead of checking out we post the cart back to the ERP as a
// PunchOutOrderMessage. The ERP runs its own approvals and later sends the
// real order (usually EDI 850 or cXML OrderRequest).
package cxml

import (
	"encoding/xml"
	"errors"
	"fmt"
	"strings"
	"time"
)

type credential struct {
	Domain       string `xml:"domain,attr"`
	Identity     string `xml:"Identity"`
	SharedSecret string `xml:"SharedSecret"`
}

type setupEnvelope struct {
	XMLName   xml.Name `xml:"cXML"`
	PayloadID string   `xml:"payloadID,attr"`
	Header    struct {
		From   struct{ Credential credential } `xml:"From"`
		To     struct{ Credential credential } `xml:"To"`
		Sender struct {
			Credential credential
			UserAgent  string `xml:"UserAgent"`
		} `xml:"Sender"`
	} `xml:"Header"`
	Request struct {
		Setup *struct {
			Operation   string `xml:"operation,attr"`
			BuyerCookie string `xml:"BuyerCookie"`
			Extrinsics  []struct {
				Name  string `xml:"name,attr"`
				Value string `xml:",chardata"`
			} `xml:"Extrinsic"`
			BrowserFormPost struct {
				URL string `xml:"URL"`
			} `xml:"BrowserFormPost"`
		} `xml:"PunchOutSetupRequest"`
	} `xml:"Request"`
}

type SetupRequest struct {
	PayloadID    string            `json:"payloadId"`
	FromIdentity string            `json:"fromIdentity"`
	SenderSecret string            `json:"-"`
	UserAgent    string            `json:"userAgent"`
	Operation    string            `json:"operation"`
	BuyerCookie  string            `json:"buyerCookie"`
	ReturnURL    string            `json:"returnUrl"`
	Extrinsics   map[string]string `json:"extrinsics"`
}

func ParseSetupRequest(raw []byte) (*SetupRequest, error) {
	var env setupEnvelope
	if err := xml.Unmarshal(raw, &env); err != nil {
		return nil, fmt.Errorf("invalid cXML: %w", err)
	}
	if env.Request.Setup == nil {
		return nil, errors.New("cXML does not contain a PunchOutSetupRequest")
	}
	s := env.Request.Setup
	req := &SetupRequest{
		PayloadID:    env.PayloadID,
		FromIdentity: strings.TrimSpace(env.Header.From.Credential.Identity),
		SenderSecret: strings.TrimSpace(env.Header.Sender.Credential.SharedSecret),
		UserAgent:    strings.TrimSpace(env.Header.Sender.UserAgent),
		Operation:    s.Operation,
		BuyerCookie:  strings.TrimSpace(s.BuyerCookie),
		ReturnURL:    strings.TrimSpace(s.BrowserFormPost.URL),
		Extrinsics:   map[string]string{},
	}
	for _, e := range s.Extrinsics {
		req.Extrinsics[e.Name] = strings.TrimSpace(e.Value)
	}
	if req.FromIdentity == "" || req.BuyerCookie == "" || req.ReturnURL == "" {
		return nil, errors.New("PunchOutSetupRequest needs From identity, BuyerCookie and BrowserFormPost URL")
	}
	return req, nil
}

func header(payloadID string, ts time.Time) string {
	return fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE cXML SYSTEM "http://xml.cxml.org/schemas/cXML/1.2.014/cXML.dtd">
<cXML payloadID="%s" timestamp="%s" xml:lang="en-US">`, esc(payloadID), ts.Format(time.RFC3339))
}

// BuildSetupResponse answers a setup request with the URL the buyer's browser opens.
func BuildSetupResponse(payloadID string, ts time.Time, code int, text, startURL string) string {
	var b strings.Builder
	b.WriteString(header(payloadID, ts))
	fmt.Fprintf(&b, "\n  <Response>\n    <Status code=\"%d\" text=\"%s\"/>", code, esc(text))
	if startURL != "" {
		fmt.Fprintf(&b, "\n    <PunchOutSetupResponse>\n      <StartPage><URL>%s</URL></StartPage>\n    </PunchOutSetupResponse>", esc(startURL))
	}
	b.WriteString("\n  </Response>\n</cXML>")
	return b.String()
}

type Item struct {
	SupplierPartID string
	Quantity       int
	UnitPrice      float64
	Description    string
	UOM            string
	UNSPSC         string
	MfrPartID      string
	MfrName        string
}

// BuildOrderMessage is the cart handed back to the ERP for its own approval workflow.
func BuildOrderMessage(payloadID string, ts time.Time, fromIdentity, buyerCookie string, items []Item) string {
	total := 0.0
	for _, it := range items {
		total += float64(it.Quantity) * it.UnitPrice
	}
	var b strings.Builder
	b.WriteString(header(payloadID, ts))
	fmt.Fprintf(&b, `
  <Header>
    <From><Credential domain="DUNS"><Identity>MROMIRROR</Identity></Credential></From>
    <To><Credential domain="NetworkID"><Identity>%s</Identity></Credential></To>
    <Sender><Credential domain="DUNS"><Identity>MROMIRROR</Identity></Credential><UserAgent>MRO Mirror PunchOut</UserAgent></Sender>
  </Header>
  <Message>
    <PunchOutOrderMessage>
      <BuyerCookie>%s</BuyerCookie>
      <PunchOutOrderMessageHeader operationAllowed="edit">
        <Total><Money currency="USD">%.2f</Money></Total>
      </PunchOutOrderMessageHeader>`, esc(fromIdentity), esc(buyerCookie), total)
	for _, it := range items {
		fmt.Fprintf(&b, `
      <ItemIn quantity="%d">
        <ItemID><SupplierPartID>%s</SupplierPartID></ItemID>
        <ItemDetail>
          <UnitPrice><Money currency="USD">%.2f</Money></UnitPrice>
          <Description xml:lang="en">%s</Description>
          <UnitOfMeasure>%s</UnitOfMeasure>
          <Classification domain="UNSPSC">%s</Classification>
          <ManufacturerPartID>%s</ManufacturerPartID>
          <ManufacturerName>%s</ManufacturerName>
        </ItemDetail>
      </ItemIn>`, it.Quantity, esc(it.SupplierPartID), it.UnitPrice, esc(it.Description), esc(it.UOM), esc(it.UNSPSC), esc(it.MfrPartID), esc(it.MfrName))
	}
	b.WriteString("\n    </PunchOutOrderMessage>\n  </Message>\n</cXML>")
	return b.String()
}

func esc(s string) string {
	var b strings.Builder
	_ = xml.EscapeText(&b, []byte(s))
	return b.String()
}

// SampleSetupRequest is what an ERP would post; the simulator pre-fills it.
func SampleSetupRequest(identity, secret, erp, email, cookie, returnURL string, ts time.Time) string {
	return fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE cXML SYSTEM "http://xml.cxml.org/schemas/cXML/1.2.014/cXML.dtd">
<cXML payloadID="%d.setup@erp.example" timestamp="%s" xml:lang="en-US">
  <Header>
    <From><Credential domain="NetworkID"><Identity>%s</Identity></Credential></From>
    <To><Credential domain="DUNS"><Identity>MROMIRROR</Identity></Credential></To>
    <Sender>
      <Credential domain="NetworkID"><Identity>%s</Identity><SharedSecret>%s</SharedSecret></Credential>
      <UserAgent>%s</UserAgent>
    </Sender>
  </Header>
  <Request deploymentMode="test">
    <PunchOutSetupRequest operation="create">
      <BuyerCookie>%s</BuyerCookie>
      <Extrinsic name="UserEmail">%s</Extrinsic>
      <BrowserFormPost><URL>%s</URL></BrowserFormPost>
    </PunchOutSetupRequest>
  </Request>
</cXML>`, ts.Unix(), ts.Format(time.RFC3339), esc(identity), esc(identity), esc(secret), esc(erp), esc(cookie), esc(email), esc(returnURL))
}
