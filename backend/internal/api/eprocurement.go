package api

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/cxml"
	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/edi"
	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/governance"
)

const ourISA = "MROMIRROR"

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func readBody(r *http.Request) (string, error) {
	b, err := io.ReadAll(http.MaxBytesReader(nil, r.Body, 1<<20))
	if err != nil {
		return "", badRequest("could not read body")
	}
	return string(b), nil
}

// ---------------------------------------------------------------- PunchOut

type punchoutIdentity struct {
	Identity      string `json:"identity"`
	ERP           string `json:"erp"`
	CorporationID string `json:"corporationId"`
	Corporation   string `json:"corporation"`
	DefaultUserID string `json:"defaultUserId"`
	DefaultEmail  string `json:"defaultEmail"`
	sharedSecret  string
}

func (s *Server) loadIdentities(ctx context.Context) ([]punchoutIdentity, error) {
	return collect(ctx, s.pool, func(r pgx.Row, p *punchoutIdentity) error {
		return r.Scan(&p.Identity, &p.ERP, &p.CorporationID, &p.Corporation, &p.DefaultUserID, &p.DefaultEmail, &p.sharedSecret)
	}, `SELECT i.identity, i.erp_name, i.corporation_id, c.name, i.default_user_id, u.email, i.shared_secret
		FROM procurement.punchout_identities i
		JOIN accounts.org_units c ON c.id = i.corporation_id
		JOIN accounts.users u ON u.id = i.default_user_id ORDER BY i.identity`)
}

func (s *Server) handlePunchoutIdentities(w http.ResponseWriter, r *http.Request) {
	ids, err := s.loadIdentities(r.Context())
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, ids)
}

// handlePunchoutSample plays the buyer's ERP: it returns the setup request
// that ERP would post, shared secret included, so the flow can be exercised.
func (s *Server) handlePunchoutSample(w http.ResponseWriter, r *http.Request) {
	ids, err := s.loadIdentities(r.Context())
	if err != nil {
		writeErr(w, err)
		return
	}
	want := r.URL.Query().Get("identity")
	email := r.URL.Query().Get("email")
	for _, id := range ids {
		if id.Identity == want || want == "" {
			if email == "" {
				email = id.DefaultEmail
			}
			doc := cxml.SampleSetupRequest(id.Identity, id.sharedSecret, id.ERP, email,
				"COOKIE-"+strings.ToUpper(randomHex(4)), s.publicURL+"/erp/return", s.now())
			w.Header().Set("Content-Type", "application/xml")
			_, _ = io.WriteString(w, doc)
			return
		}
	}
	writeErr(w, notFound("no PunchOut identity "+want))
}

func (s *Server) handlePunchoutSetup(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	body, err := readBody(r)
	if err != nil {
		writeErr(w, err)
		return
	}
	payload := fmt.Sprintf("%d.%s@mromirror", s.now().Unix(), randomHex(4))
	respond := func(status, code int, text, url string) {
		w.Header().Set("Content-Type", "application/xml")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, cxml.BuildSetupResponse(payload, s.now(), code, text, url))
	}

	req, err := cxml.ParseSetupRequest([]byte(body))
	if err != nil {
		respond(http.StatusBadRequest, 400, err.Error(), "")
		return
	}
	var secret, corpID, defaultUser string
	err = s.pool.QueryRow(ctx, `SELECT shared_secret, corporation_id, default_user_id FROM procurement.punchout_identities WHERE identity = $1`,
		req.FromIdentity).Scan(&secret, &corpID, &defaultUser)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && subtle.ConstantTimeCompare([]byte(secret), []byte(req.SenderSecret)) != 1) {
		respond(http.StatusUnauthorized, 401, "Unknown identity or bad shared secret", "")
		return
	} else if err != nil {
		writeErr(w, err)
		return
	}
	userID := defaultUser
	if email := req.Extrinsics["UserEmail"]; email != "" {
		var id string
		if err := s.pool.QueryRow(ctx, `SELECT id FROM accounts.users WHERE lower(email) = lower($1) AND corporation_id = $2`, email, corpID).Scan(&id); err == nil {
			userID = id
		}
	}
	id := randomHex(12)
	if _, err := s.pool.Exec(ctx, `INSERT INTO procurement.punchout_sessions (id, identity, user_id, buyer_cookie, return_url) VALUES ($1,$2,$3,$4,$5)`,
		id, req.FromIdentity, userID, req.BuyerCookie, req.ReturnURL); err != nil {
		writeErr(w, err)
		return
	}
	respond(http.StatusOK, 200, "OK", s.publicURL+"/punchout/"+id)
}

type punchoutSession struct {
	ID           string    `json:"id"`
	Identity     string    `json:"identity"`
	ERP          string    `json:"erp"`
	UserID       string    `json:"userId"`
	UserName     string    `json:"userName"`
	Corporation  string    `json:"corporation"`
	BuyerCookie  string    `json:"buyerCookie"`
	ReturnURL    string    `json:"returnUrl"`
	Status       string    `json:"status"`
	OrderMessage *string   `json:"orderMessage"`
	CreatedAt    time.Time `json:"createdAt"`
}

func (s *Server) loadSession(ctx context.Context, q dbtx, id string, lock bool) (*punchoutSession, error) {
	sql := `SELECT s.id, s.identity, i.erp_name, s.user_id, u.name, c.name, s.buyer_cookie, s.return_url, s.status, s.order_message, s.created_at
		FROM procurement.punchout_sessions s
		JOIN procurement.punchout_identities i ON i.identity = s.identity
		JOIN accounts.users u ON u.id = s.user_id
		JOIN accounts.org_units c ON c.id = u.corporation_id WHERE s.id = $1`
	if lock {
		sql += ` FOR UPDATE OF s`
	}
	var p punchoutSession
	err := q.QueryRow(ctx, sql, id).Scan(&p.ID, &p.Identity, &p.ERP, &p.UserID, &p.UserName, &p.Corporation, &p.BuyerCookie,
		&p.ReturnURL, &p.Status, &p.OrderMessage, &p.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, notFound("no PunchOut session " + id)
	}
	return &p, err
}

func (s *Server) handlePunchoutSession(w http.ResponseWriter, r *http.Request) {
	p, err := s.loadSession(r.Context(), s.pool, r.PathValue("id"), false)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, p)
}

// handlePunchoutReturn transfers the cart back to the ERP instead of checking out.
func (s *Server) handlePunchoutReturn(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var body struct {
		Lines []lineReq `json:"lines"`
	}
	if err := decode(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	if len(body.Lines) == 0 {
		writeErr(w, badRequest("cart is empty"))
		return
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		writeErr(w, err)
		return
	}
	defer tx.Rollback(ctx)
	sess, err := s.loadSession(ctx, tx, r.PathValue("id"), true)
	if err != nil {
		writeErr(w, err)
		return
	}
	if sess.Status != "open" {
		writeErr(w, conflict("this PunchOut session already returned its cart"))
		return
	}
	u, err := s.loadUser(ctx, tx, sess.UserID)
	if err != nil {
		writeErr(w, err)
		return
	}
	book, err := s.loadPriceBook(ctx, tx, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}
	skus := []string{}
	for _, l := range body.Lines {
		skus = append(skus, l.SKU)
	}
	prods, err := s.loadProducts(ctx, tx, skus)
	if err != nil {
		writeErr(w, err)
		return
	}

	items := []cxml.Item{}
	po := edi.PurchaseOrder{PONumber: fmt.Sprintf("PO-%d", 4500000000+s.now().Unix()%100000000)}
	total := 0.0
	for i, l := range body.Lines {
		p := prods[l.SKU]
		if p == nil || l.Qty <= 0 {
			writeErr(w, badRequest("invalid cart line "+l.SKU))
			return
		}
		price := book.quote(p, l.Qty)
		total += price.Extended
		items = append(items, cxml.Item{SupplierPartID: p.SKU, Quantity: l.Qty, UnitPrice: price.UnitPrice, Description: p.Name,
			UOM: p.UOM, UNSPSC: book.cats[p.CategoryID].UNSPSC, MfrPartID: p.MfrPartNumber, MfrName: p.Brand})
		po.Lines = append(po.Lines, edi.POLine{LineNo: fmt.Sprint(i + 1), Qty: l.Qty, UOM: p.UOM, UnitPrice: price.UnitPrice,
			VendorPart: p.SKU, Desc: p.Name})
	}
	msg := cxml.BuildOrderMessage(fmt.Sprintf("%d.%s@mromirror", s.now().Unix(), randomHex(4)), s.now(), sess.Identity, sess.BuyerCookie, items)
	if _, err := tx.Exec(ctx, `UPDATE procurement.punchout_sessions SET status = 'returned', order_message = $2 WHERE id = $1`, sess.ID, msg); err != nil {
		writeErr(w, err)
		return
	}

	// After the ERP approves the requisition it transmits a real purchase order.
	// Pre-build that EDI 850 so the round trip can be completed in the simulator.
	var isa, ccCode, facCode, facName string
	_ = tx.QueryRow(ctx, `SELECT isa_id FROM procurement.edi_partners WHERE corporation_id = $1 ORDER BY isa_id LIMIT 1`, u.CorporationID).Scan(&isa)
	_ = tx.QueryRow(ctx, `SELECT cc.code, f.code, f.name FROM accounts.org_units cc JOIN accounts.org_units f ON f.id = cc.parent_id WHERE cc.id = $1`,
		u.CostCenterID).Scan(&ccCode, &facCode, &facName)
	po.Department, po.ShipToCode, po.ShipToName = ccCode, facCode, facName
	edi850 := ""
	if isa != "" {
		edi850 = edi.Build850(edi.Interchange{SenderID: isa, ReceiverID: ourISA, Control: int(s.now().Unix() % 1e9), Time: s.now()}, po)
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"session": sess.ID, "erp": sess.ERP, "returnUrl": sess.ReturnURL, "orderMessage": msg,
		"total": math.Round(total*100) / 100, "edi850": edi850,
	})
}

// ---------------------------------------------------------------- EDI

type ediLineResult struct {
	LineNo   string  `json:"lineNo"`
	POPart   string  `json:"poPart"`
	SKU      string  `json:"sku"`
	Qty      int     `json:"qty"`
	POPrice  float64 `json:"poPrice"`
	OurPrice float64 `json:"ourPrice"`
	Status   string  `json:"status"`
	Note     string  `json:"note"`
}

func (s *Server) handleEDI850(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	body, err := readBody(r)
	if err != nil {
		writeErr(w, err)
		return
	}
	po, err := edi.Parse850(body)
	if err != nil {
		writeErr(w, badRequest("850 rejected: "+err.Error()))
		return
	}
	var corpID, partnerName, defaultUser string
	err = s.pool.QueryRow(ctx, `SELECT corporation_id, name, default_user_id FROM procurement.edi_partners WHERE isa_id = $1`, po.SenderID).
		Scan(&corpID, &partnerName, &defaultUser)
	if errors.Is(err, pgx.ErrNoRows) {
		writeErr(w, badRequest("850 rejected: unknown trading partner "+po.SenderID))
		return
	} else if err != nil {
		writeErr(w, err)
		return
	}
	u, err := s.loadUser(ctx, s.pool, defaultUser)
	if err != nil {
		writeErr(w, err)
		return
	}

	// Map the N1 ship-to code to a facility and REF*DP to one of its cost centers.
	units, err := s.loadOrgUnits(ctx, s.pool, corpID)
	if err != nil {
		writeErr(w, err)
		return
	}
	costCenter := u.CostCenterID
	for _, f := range units {
		if f.Kind != "facility" || f.Code != po.ShipToCode {
			continue
		}
		var first string
		matched := false
		for _, cc := range units {
			if cc.Kind != "cost_center" || cc.ParentID == nil || *cc.ParentID != f.ID {
				continue
			}
			if first == "" {
				first = cc.ID
			}
			if cc.Code == po.Department {
				costCenter, matched = cc.ID, true
			}
		}
		if !matched && first != "" {
			costCenter = first
		}
	}

	book, err := s.loadPriceBook(ctx, s.pool, corpID)
	if err != nil {
		writeErr(w, err)
		return
	}
	results := []ediLineResult{}
	accepted := []lineReq{}
	for _, l := range po.Lines {
		res := ediLineResult{LineNo: l.LineNo, POPart: firstNonEmpty(l.VendorPart, l.MfrPart), Qty: l.Qty, POPrice: l.UnitPrice}
		p, note, err := s.resolveEDIPart(ctx, l)
		if err != nil {
			writeErr(w, err)
			return
		}
		res.Note = note
		if p == nil {
			res.Status, res.Note = "IR", "unknown item"
			results = append(results, res)
			continue
		}
		res.SKU = p.SKU
		res.OurPrice = book.quote(p, l.Qty).UnitPrice
		res.Status = "IA"
		if math.Abs(res.OurPrice-l.UnitPrice) > 0.005 {
			res.Status = "IP"
			res.Note = strings.TrimSpace(res.Note + fmt.Sprintf(" PO price $%.2f differs from contract price $%.2f", l.UnitPrice, res.OurPrice))
		}
		results = append(results, res)
		accepted = append(accepted, lineReq{SKU: p.SKU, Qty: l.Qty})
	}

	var orderID string
	var ev *evaluation
	outcome := "rejected"
	if len(accepted) > 0 {
		orderID, ev, err = s.placeOrder(ctx, u, orderReq{Lines: accepted, CostCenterID: costCenter, PONumber: po.PONumber, Source: "edi"})
		var ae *apiError
		switch {
		case err == nil:
			outcome = "accepted"
		case errors.As(err, &ae) && ev != nil && ev.Governance.Outcome == governance.Blocked:
			for i := range results {
				if results[i].Status != "IR" {
					results[i].Status = "IR"
					results[i].Note = "rejected by purchasing governance"
				}
			}
		default:
			writeErr(w, err)
			return
		}
	}
	if ev != nil && outcome == "accepted" {
		back := map[string]bool{}
		for _, b := range ev.Plan.Backorders {
			back[b.SKU] = true
		}
		for i := range results {
			if results[i].Status == "IA" && back[results[i].SKU] {
				results[i].Status = "IB"
				results[i].Note = "accepted; part of the quantity is backordered"
			}
		}
	}

	ack := []edi.AckLine{}
	for _, r := range results {
		price := r.OurPrice
		if r.Status == "IR" {
			price = r.POPrice
		}
		ack = append(ack, edi.AckLine{LineNo: r.LineNo, Qty: r.Qty, UOM: "EA", UnitPrice: price, VendorPart: firstNonEmpty(r.SKU, r.POPart), Status: r.Status})
	}
	for i, l := range po.Lines {
		if l.UOM != "" {
			ack[i].UOM = l.UOM
		}
	}
	doc855 := edi.Build855(edi.Interchange{SenderID: ourISA, ReceiverID: po.SenderID, Control: int(s.now().UnixNano() % 1e9), Time: s.now()}, po.PONumber, ack)

	var oid *string
	if orderID != "" {
		oid = &orderID
	}
	if _, err := s.pool.Exec(ctx, `INSERT INTO procurement.edi_messages (direction, doc_type, partner, order_id, body) VALUES
		('inbound', '850', $1, $2, $3), ('outbound', '855', $1, $2, $4)`, po.SenderID, oid, body, doc855); err != nil {
		writeErr(w, err)
		return
	}
	resp := map[string]any{"po": po, "partner": partnerName, "orderId": orderID, "outcome": outcome, "lines": results, "ack855": doc855}
	if ev != nil {
		resp["governance"] = ev.Governance
		resp["plan"] = ev.Plan
	}
	writeJSON(w, http.StatusOK, resp)
}

// resolveEDIPart finds the SKU a PO1 line means: our part number, the
// replacement of a discontinued one, or a cross-referenced competitor/OEM number.
func (s *Server) resolveEDIPart(ctx context.Context, l edi.POLine) (*product, string, error) {
	if l.VendorPart != "" {
		sku := strings.ToUpper(l.VendorPart)
		prods, err := s.loadProducts(ctx, s.pool, []string{sku})
		if err != nil {
			return nil, "", err
		}
		if p := prods[sku]; p != nil {
			if p.Status == "active" {
				return p, "", nil
			}
			if p.ReplacementSKU != nil {
				rp, err := s.loadProducts(ctx, s.pool, []string{*p.ReplacementSKU})
				return rp[*p.ReplacementSKU], fmt.Sprintf("%s discontinued; substituted %s", p.SKU, *p.ReplacementSKU), err
			}
		}
	}
	for _, part := range []string{l.VendorPart, l.MfrPart} {
		if part == "" {
			continue
		}
		hits, err := s.resolveCrossRefs(ctx, part)
		if err != nil || len(hits) == 0 {
			continue
		}
		prods, err := s.loadProducts(ctx, s.pool, []string{hits[0].SKU})
		return prods[hits[0].SKU], fmt.Sprintf("cross-referenced %s %s", hits[0].RefBrand, hits[0].RefPartNumber), err
	}
	return nil, "", nil
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if s != "" {
			return s
		}
	}
	return ""
}

func (s *Server) handleEDIMessages(w http.ResponseWriter, r *http.Request) {
	type msg struct {
		ID        int       `json:"id"`
		Direction string    `json:"direction"`
		DocType   string    `json:"docType"`
		Partner   string    `json:"partner"`
		OrderID   *string   `json:"orderId"`
		Body      string    `json:"body"`
		CreatedAt time.Time `json:"createdAt"`
	}
	msgs, err := collect(r.Context(), s.pool, func(r pgx.Row, m *msg) error {
		return r.Scan(&m.ID, &m.Direction, &m.DocType, &m.Partner, &m.OrderID, &m.Body, &m.CreatedAt)
	}, `SELECT id, direction, doc_type, partner, order_id, body, created_at FROM procurement.edi_messages ORDER BY id DESC LIMIT 50`)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, msgs)
}
