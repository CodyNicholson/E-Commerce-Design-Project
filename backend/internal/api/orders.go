package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/governance"
	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/pricing"
	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/sourcing"
)

type lineReq struct {
	SKU string `json:"sku"`
	Qty int    `json:"qty"`
	// Ref links a KeepStock replenishment line to its bin or vending slot.
	Ref string `json:"ref,omitempty"`
}

type orderReq struct {
	Lines           []lineReq `json:"lines"`
	CostCenterID    string    `json:"costCenterId"`
	PONumber        string    `json:"poNumber"`
	BlanketPOID     string    `json:"blanketPoId"`
	DeliveryMethod  string    `json:"deliveryMethod"`
	FulfillmentPref string    `json:"fulfillmentPref"`
	Source          string    `json:"-"`
}

func (r *orderReq) applyDefaults() {
	if r.DeliveryMethod == "" {
		r.DeliveryMethod = "ship"
	}
	if r.FulfillmentPref == "" {
		r.FulfillmentPref = "ship_available"
	}
}

type pricedLine struct {
	LineNo       int                     `json:"lineNo"`
	SKU          string                  `json:"sku"`
	Name         string                  `json:"name"`
	Brand        string                  `json:"brand"`
	UOM          string                  `json:"uom"`
	Qty          int                     `json:"qty"`
	UnitPrice    float64                 `json:"unitPrice"`
	Extended     float64                 `json:"extended"`
	Price        pricing.Result          `json:"price"`
	Hazmat       bool                    `json:"hazmat"`
	DropShipOnly bool                    `json:"dropShipOnly"`
	Restriction  *governance.Restriction `json:"restriction,omitempty"`
	Ref          string                  `json:"ref,omitempty"`
}

type evaluation struct {
	Lines      []pricedLine      `json:"lines"`
	Subtotal   float64           `json:"subtotal"`
	TaxRate    float64           `json:"taxRate"`
	Tax        float64           `json:"tax"`
	TaxNote    string            `json:"taxNote"`
	Total      float64           `json:"total"`
	Governance governance.Result `json:"governance"`
	Plan       sourcing.Plan     `json:"plan"`
	Facility   map[string]string `json:"facility"`
	CostCenter map[string]string `json:"costCenter"`
	MTDSpend   float64           `json:"mtdSpend"`

	facility     *facility
	costCenterID string
	blanketPOID  *string
}

// evaluate is the checkout pipeline shared by web checkout, PunchOut, EDI and
// KeepStock: price every line (Pricing), compute tax with exemptions
// (Accounts), run purchasing governance (Accounts/Procurement) and plan
// sourcing against live ATP (OMS). It has no side effects.
func (s *Server) evaluate(ctx context.Context, q dbtx, u *user, req orderReq) (*evaluation, error) {
	if len(req.Lines) == 0 {
		return nil, badRequest("order has no lines")
	}
	req.applyDefaults()
	if req.DeliveryMethod != "ship" && req.DeliveryMethod != "will_call" {
		return nil, badRequest("deliveryMethod must be ship or will_call")
	}
	if req.FulfillmentPref != "ship_available" && req.FulfillmentPref != "ship_complete" {
		return nil, badRequest("fulfillmentPref must be ship_available or ship_complete")
	}
	if req.CostCenterID == "" {
		req.CostCenterID = u.CostCenterID
	}

	var ccName, ccCode, facID string
	err := q.QueryRow(ctx, `SELECT name, code, parent_id FROM accounts.org_units WHERE id = $1 AND kind = 'cost_center'`, req.CostCenterID).
		Scan(&ccName, &ccCode, &facID)
	if err == pgx.ErrNoRows {
		return nil, badRequest("unknown cost center " + req.CostCenterID)
	} else if err != nil {
		return nil, err
	}
	fac, err := s.loadFacility(ctx, q, facID)
	if err != nil {
		return nil, err
	}
	if fac.CorporationID != u.CorporationID {
		return nil, forbidden("cost center belongs to another corporation")
	}

	skus := []string{}
	for _, l := range req.Lines {
		if l.Qty <= 0 {
			return nil, badRequest("quantity must be positive for " + l.SKU)
		}
		skus = append(skus, l.SKU)
	}
	prods, err := s.loadProducts(ctx, q, skus)
	if err != nil {
		return nil, err
	}
	book, err := s.loadPriceBook(ctx, q, u.CorporationID)
	if err != nil {
		return nil, err
	}

	ev := &evaluation{facility: fac, costCenterID: req.CostCenterID,
		Facility:   map[string]string{"id": fac.ID, "name": fac.Name, "state": fac.State},
		CostCenter: map[string]string{"id": req.CostCenterID, "name": ccName, "code": ccCode}}
	govLines := []governance.Line{}
	srcLines := []sourcing.Line{}
	for i, l := range req.Lines {
		p := prods[l.SKU]
		if p == nil {
			return nil, badRequest("unknown SKU " + l.SKU)
		}
		res := book.quote(p, l.Qty)
		pl := pricedLine{LineNo: i + 1, SKU: p.SKU, Name: p.Name, Brand: p.Brand, UOM: p.UOM, Qty: l.Qty,
			UnitPrice: res.UnitPrice, Extended: res.Extended, Price: res, Hazmat: p.hazmat(), DropShipOnly: p.DropShipOnly,
			Restriction: book.restriction(p.CategoryID), Ref: l.Ref}
		ev.Lines = append(ev.Lines, pl)
		ev.Subtotal += res.Extended
		govLines = append(govLines, governance.Line{SKU: p.SKU, Name: p.Name, Discontinued: p.Status == "discontinued", Restriction: pl.Restriction})
		srcLines = append(srcLines, sourcing.Line{SKU: p.SKU, Qty: l.Qty, Hazmat: p.hazmat(), DropShipOnly: p.DropShipOnly})
	}
	ev.Subtotal = pricing.Round2(ev.Subtotal)

	// Tax: state rate unless the corporation holds a current exemption certificate for that state.
	var rate float64
	if err := q.QueryRow(ctx, `SELECT coalesce((SELECT rate FROM pricing.tax_rates WHERE state = $1), 0)::float8`, fac.State).Scan(&rate); err != nil {
		return nil, err
	}
	var cert, certType string
	var expires time.Time
	err = q.QueryRow(ctx, `SELECT certificate_number, exemption_type, expires_on FROM accounts.tax_exemptions
		WHERE corporation_id = $1 AND state = $2 ORDER BY expires_on DESC LIMIT 1`, u.CorporationID, fac.State).Scan(&cert, &certType, &expires)
	switch {
	case err == nil && !s.today().After(expires):
		ev.TaxNote = fmt.Sprintf("Exempt: %s (%s)", cert, certType)
	case err == nil:
		ev.TaxRate = rate
		ev.TaxNote = fmt.Sprintf("%s %.2f%% (exemption %s expired %s)", fac.State, rate*100, cert, expires.Format("2006-01-02"))
	case err == pgx.ErrNoRows:
		ev.TaxRate = rate
		ev.TaxNote = fmt.Sprintf("%s %.2f%%", fac.State, rate*100)
	default:
		return nil, err
	}
	ev.Tax = pricing.Round2(ev.Subtotal * ev.TaxRate)
	ev.Total = pricing.Round2(ev.Subtotal + ev.Tax)

	// Governance
	chain, err := s.approverChain(ctx, q, u.ID)
	if err != nil {
		return nil, err
	}
	if ev.MTDSpend, err = s.mtdSpend(ctx, q, u.ID); err != nil {
		return nil, err
	}
	gin := governance.Input{
		Buyer: governance.Buyer{ID: u.ID, PerOrderLimit: u.PerOrderLimit, MonthlyLimit: u.MonthlyLimit,
			MTDSpend: ev.MTDSpend, Certs: u.Certs, Chain: chain},
		Lines: govLines, Total: ev.Total, FacilityID: fac.ID, PONumber: req.PONumber, Source: req.Source, Today: s.today(),
	}
	if req.BlanketPOID != "" {
		var b governance.BlanketPO
		var bfac *string
		err := q.QueryRow(ctx, `SELECT po_number, facility_id, amount_authorized, amount_consumed, valid_from, valid_to
			FROM accounts.blanket_pos WHERE id = $1 AND corporation_id = $2`, req.BlanketPOID, u.CorporationID).
			Scan(&b.Number, &bfac, &b.Authorized, &b.Consumed, &b.ValidFrom, &b.ValidTo)
		if err == pgx.ErrNoRows {
			return nil, badRequest("unknown blanket PO " + req.BlanketPOID)
		} else if err != nil {
			return nil, err
		}
		if bfac != nil {
			b.FacilityID = *bfac
		}
		gin.BlanketPO = &b
		ev.blanketPOID = &req.BlanketPOID
	}
	ev.Governance = governance.Evaluate(gin)

	// Sourcing
	nodes, err := s.loadNodes(ctx, q)
	if err != nil {
		return nil, err
	}
	atp, err := s.loadATP(ctx, q, skus, false)
	if err != nil {
		return nil, err
	}
	ev.Plan = sourcing.Build(sourcing.Request{Lines: srcLines, Nodes: nodes, ATP: atp, Lat: fac.Lat, Lon: fac.Lon,
		DeliveryMethod: req.DeliveryMethod, Pref: req.FulfillmentPref})
	return ev, nil
}

func (s *Server) handlePreview(w http.ResponseWriter, r *http.Request, u *user) {
	var req orderReq
	if err := decode(r, &req); err != nil {
		writeErr(w, err)
		return
	}
	req.Source = "web"
	ev, err := s.evaluate(r.Context(), s.pool, u, req)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, ev)
}

func (s *Server) handleCreateOrder(w http.ResponseWriter, r *http.Request, u *user) {
	var req orderReq
	if err := decode(r, &req); err != nil {
		writeErr(w, err)
		return
	}
	req.Source = "web"
	id, ev, err := s.placeOrder(r.Context(), u, req)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"orderId": id, "evaluation": ev})
}

// placeOrder evaluates and persists an order in one transaction. Blocked
// orders are refused; auto-approved orders are released to the warehouse
// immediately; the rest wait in the approver's queue.
func (s *Server) placeOrder(ctx context.Context, u *user, req orderReq) (string, *evaluation, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", nil, err
	}
	defer tx.Rollback(ctx)
	id, ev, err := s.placeOrderTx(ctx, tx, u, req)
	if err != nil {
		return "", ev, err
	}
	return id, ev, tx.Commit(ctx)
}

func (s *Server) placeOrderTx(ctx context.Context, tx pgx.Tx, u *user, req orderReq) (string, *evaluation, error) {
	req.applyDefaults()
	ev, err := s.evaluate(ctx, tx, u, req)
	if err != nil {
		return "", nil, err
	}
	if ev.Governance.Outcome == governance.Blocked {
		return "", ev, &apiError{http.StatusUnprocessableEntity, "order blocked by purchasing governance"}
	}
	var seq int64
	if err := tx.QueryRow(ctx, `SELECT nextval('oms.order_seq')`).Scan(&seq); err != nil {
		return "", nil, err
	}
	id := fmt.Sprintf("ORD-%d", seq)
	status := "released"
	var approverID *string
	if ev.Governance.Outcome == governance.ApprovalRequired {
		status = "pending_approval"
		approverID = &ev.Governance.Approver.ID
	}
	gov, _ := json.Marshal(ev.Governance.Checks)
	_, err = tx.Exec(ctx, `INSERT INTO oms.orders (id, user_id, corporation_id, facility_id, cost_center_id, po_number, blanket_po_id,
		source, status, delivery_method, fulfillment_pref, subtotal, tax, total, tax_note, approver_id, governance)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17)`,
		id, u.ID, u.CorporationID, ev.facility.ID, ev.costCenterID, req.PONumber, ev.blanketPOID, req.Source, status,
		req.DeliveryMethod, req.FulfillmentPref, ev.Subtotal, ev.Tax, ev.Total, ev.TaxNote, approverID, gov)
	if err != nil {
		return "", nil, err
	}
	for _, l := range ev.Lines {
		var ref *string
		if l.Ref != "" {
			ref = &l.Ref
		}
		if _, err := tx.Exec(ctx, `INSERT INTO oms.order_lines (order_id, line_no, sku, qty, unit_price, price_source, extended, ref)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`, id, l.LineNo, l.SKU, l.Qty, l.UnitPrice, l.Price.Label, l.Extended, ref); err != nil {
			return "", nil, err
		}
	}
	if status == "released" {
		if err := s.release(ctx, tx, id); err != nil {
			return "", nil, err
		}
	}
	return id, ev, nil
}

// release re-plans sourcing against locked inventory, reserves stock, writes
// shipments and draws down the blanket PO.
func (s *Server) release(ctx context.Context, tx pgx.Tx, orderID string) error {
	var facID, method, pref string
	var total float64
	var bpo *string
	if err := tx.QueryRow(ctx, `SELECT facility_id, delivery_method, fulfillment_pref, total, blanket_po_id FROM oms.orders WHERE id = $1`, orderID).
		Scan(&facID, &method, &pref, &total, &bpo); err != nil {
		return err
	}
	type lineRow struct {
		sku              string
		qty              int
		hazmat, dropShip bool
	}
	lines, err := collect(ctx, tx, func(r pgx.Row, l *lineRow) error { return r.Scan(&l.sku, &l.qty, &l.hazmat, &l.dropShip) },
		`SELECT l.sku, l.qty, p.hazmat_un IS NOT NULL, p.drop_ship_only FROM oms.order_lines l
		 JOIN pim.products p ON p.sku = l.sku WHERE l.order_id = $1 ORDER BY l.line_no`, orderID)
	if err != nil {
		return err
	}
	skus := []string{}
	srcLines := []sourcing.Line{}
	for _, l := range lines {
		skus = append(skus, l.sku)
		srcLines = append(srcLines, sourcing.Line{SKU: l.sku, Qty: l.qty, Hazmat: l.hazmat, DropShipOnly: l.dropShip})
	}
	fac, err := s.loadFacility(ctx, tx, facID)
	if err != nil {
		return err
	}
	nodes, err := s.loadNodes(ctx, tx)
	if err != nil {
		return err
	}
	atp, err := s.loadATP(ctx, tx, skus, true)
	if err != nil {
		return err
	}
	plan := sourcing.Build(sourcing.Request{Lines: srcLines, Nodes: nodes, ATP: atp, Lat: fac.Lat, Lon: fac.Lon, DeliveryMethod: method, Pref: pref})

	for _, sh := range plan.Shipments {
		var shipID int
		if err := tx.QueryRow(ctx, `INSERT INTO oms.shipments (order_id, node_id, method, eta_days, eta_label, note)
			VALUES ($1,$2,$3,$4,$5,$6) RETURNING id`, orderID, sh.NodeID, sh.Method, sh.EtaDays, sh.EtaLabel, sh.Note).Scan(&shipID); err != nil {
			return err
		}
		for _, a := range sh.Lines {
			if _, err := tx.Exec(ctx, `INSERT INTO oms.shipment_lines (shipment_id, sku, qty) VALUES ($1,$2,$3)`, shipID, a.SKU, a.Qty); err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, `UPDATE oms.inventory SET reserved = reserved + $3 WHERE node_id = $1 AND sku = $2`, sh.NodeID, a.SKU, a.Qty); err != nil {
				return err
			}
		}
	}
	if len(plan.Backorders) > 0 {
		var shipID int
		if err := tx.QueryRow(ctx, `INSERT INTO oms.shipments (order_id, node_id, method, eta_days, eta_label, status, note)
			VALUES ($1, NULL, 'backorder', $2, $3, 'backordered', 'awaiting replenishment from supplier') RETURNING id`,
			orderID, plan.BackorderEtaDays, fmt.Sprintf("Backordered, ~%d business days", plan.BackorderEtaDays)).Scan(&shipID); err != nil {
			return err
		}
		for _, a := range plan.Backorders {
			if _, err := tx.Exec(ctx, `INSERT INTO oms.shipment_lines (shipment_id, sku, qty) VALUES ($1,$2,$3)`, shipID, a.SKU, a.Qty); err != nil {
				return err
			}
		}
	}
	if bpo != nil {
		if _, err := tx.Exec(ctx, `UPDATE accounts.blanket_pos SET amount_consumed = amount_consumed + $2 WHERE id = $1`, *bpo, total); err != nil {
			return err
		}
	}
	_, err = tx.Exec(ctx, `UPDATE oms.orders SET status = 'released' WHERE id = $1`, orderID)
	return err
}

// ---------------------------------------------------------------- order queries

type orderSummary struct {
	ID             string    `json:"id"`
	UserID         string    `json:"userId"`
	UserName       string    `json:"userName"`
	CostCenter     string    `json:"costCenter"`
	Facility       string    `json:"facility"`
	PONumber       string    `json:"poNumber"`
	Source         string    `json:"source"`
	Status         string    `json:"status"`
	Total          float64   `json:"total"`
	ApproverID     *string   `json:"approverId"`
	ApproverName   *string   `json:"approverName"`
	CreatedAt      time.Time `json:"createdAt"`
	LineCount      int       `json:"lineCount"`
	DeliveryMethod string    `json:"deliveryMethod"`
}

const orderSummarySQL = `SELECT o.id, o.user_id, u.name, cc.code || ' ' || cc.name, f.name, o.po_number, o.source, o.status, o.total,
	o.approver_id, a.name, o.created_at, (SELECT count(*) FROM oms.order_lines l WHERE l.order_id = o.id), o.delivery_method
	FROM oms.orders o
	JOIN accounts.users u ON u.id = o.user_id
	JOIN accounts.org_units cc ON cc.id = o.cost_center_id
	JOIN accounts.org_units f ON f.id = o.facility_id
	LEFT JOIN accounts.users a ON a.id = o.approver_id`

func scanOrderSummary(r pgx.Row, o *orderSummary) error {
	return r.Scan(&o.ID, &o.UserID, &o.UserName, &o.CostCenter, &o.Facility, &o.PONumber, &o.Source, &o.Status, &o.Total,
		&o.ApproverID, &o.ApproverName, &o.CreatedAt, &o.LineCount, &o.DeliveryMethod)
}

func (s *Server) handleListOrders(w http.ResponseWriter, r *http.Request, u *user) {
	var where string
	switch r.URL.Query().Get("scope") {
	case "approvals":
		where = ` WHERE o.approver_id = $1 AND o.status = 'pending_approval'`
	case "corporation":
		where = ` WHERE o.corporation_id = (SELECT corporation_id FROM accounts.users WHERE id = $1)`
	default:
		where = ` WHERE o.user_id = $1`
	}
	orders, err := collect(r.Context(), s.pool, scanOrderSummary, orderSummarySQL+where+` ORDER BY o.created_at DESC LIMIT 200`, u.ID)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, orders)
}

type shipmentOut struct {
	ID       int                   `json:"id"`
	NodeID   *string               `json:"nodeId"`
	NodeName *string               `json:"nodeName"`
	Method   string                `json:"method"`
	EtaDays  int                   `json:"etaDays"`
	EtaLabel string                `json:"etaLabel"`
	Status   string                `json:"status"`
	Note     string                `json:"note"`
	Lines    []sourcing.Allocation `json:"lines"`
}

func (s *Server) loadOrderDetail(ctx context.Context, q dbtx, id string) (map[string]any, error) {
	var o orderSummary
	if err := scanOrderSummary(q.QueryRow(ctx, orderSummarySQL+` WHERE o.id = $1`, id), &o); err != nil {
		if err == pgx.ErrNoRows {
			return nil, notFound("no order " + id)
		}
		return nil, err
	}
	var subtotal, tax float64
	var taxNote, corpID, method, pref string
	var gov, approvals json.RawMessage
	if err := q.QueryRow(ctx, `SELECT subtotal, tax, tax_note, corporation_id, delivery_method, fulfillment_pref, governance, approvals
		FROM oms.orders WHERE id = $1`, id).Scan(&subtotal, &tax, &taxNote, &corpID, &method, &pref, &gov, &approvals); err != nil {
		return nil, err
	}
	type lineOut struct {
		LineNo      int     `json:"lineNo"`
		SKU         string  `json:"sku"`
		Name        string  `json:"name"`
		UOM         string  `json:"uom"`
		Qty         int     `json:"qty"`
		UnitPrice   float64 `json:"unitPrice"`
		PriceSource string  `json:"priceSource"`
		Extended    float64 `json:"extended"`
		Ref         *string `json:"ref"`
	}
	lines, err := collect(ctx, q, func(r pgx.Row, l *lineOut) error {
		return r.Scan(&l.LineNo, &l.SKU, &l.Name, &l.UOM, &l.Qty, &l.UnitPrice, &l.PriceSource, &l.Extended, &l.Ref)
	}, `SELECT l.line_no, l.sku, p.name, p.uom, l.qty, l.unit_price, l.price_source, l.extended, l.ref
		FROM oms.order_lines l JOIN pim.products p ON p.sku = l.sku WHERE l.order_id = $1 ORDER BY l.line_no`, id)
	if err != nil {
		return nil, err
	}
	shipments, err := collect(ctx, q, func(r pgx.Row, sh *shipmentOut) error {
		return r.Scan(&sh.ID, &sh.NodeID, &sh.NodeName, &sh.Method, &sh.EtaDays, &sh.EtaLabel, &sh.Status, &sh.Note)
	}, `SELECT s.id, s.node_id, n.name, s.method, s.eta_days, s.eta_label, s.status, s.note
		FROM oms.shipments s LEFT JOIN oms.nodes n ON n.id = s.node_id WHERE s.order_id = $1 ORDER BY s.eta_days, s.id`, id)
	if err != nil {
		return nil, err
	}
	for i := range shipments {
		shipments[i].Lines, err = collect(ctx, q, func(r pgx.Row, a *sourcing.Allocation) error { return r.Scan(&a.SKU, &a.Qty) },
			`SELECT sku, qty FROM oms.shipment_lines WHERE shipment_id = $1`, shipments[i].ID)
		if err != nil {
			return nil, err
		}
	}
	return map[string]any{
		"order": o, "subtotal": subtotal, "tax": tax, "taxNote": taxNote, "fulfillmentPref": pref,
		"governance": gov, "approvals": approvals, "lines": lines, "shipments": shipments,
	}, nil
}

func (s *Server) handleGetOrder(w http.ResponseWriter, r *http.Request, u *user) {
	d, err := s.loadOrderDetail(r.Context(), s.pool, r.PathValue("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}

// ---------------------------------------------------------------- approvals

type approvalEntry struct {
	By     string    `json:"by"`
	ByName string    `json:"byName"`
	Action string    `json:"action"` // approved | escalated | rejected
	Note   string    `json:"note"`
	At     time.Time `json:"at"`
}

func (s *Server) lockPending(ctx context.Context, tx pgx.Tx, id string, u *user) (float64, []approvalEntry, error) {
	var status string
	var approver *string
	var total float64
	var raw []byte
	err := tx.QueryRow(ctx, `SELECT status, approver_id, total, approvals FROM oms.orders WHERE id = $1 FOR UPDATE`, id).
		Scan(&status, &approver, &total, &raw)
	if err == pgx.ErrNoRows {
		return 0, nil, notFound("no order " + id)
	} else if err != nil {
		return 0, nil, err
	}
	if status != "pending_approval" {
		return 0, nil, conflict("order is " + status)
	}
	if approver == nil || *approver != u.ID {
		return 0, nil, forbidden("order is waiting on a different approver")
	}
	var hist []approvalEntry
	_ = json.Unmarshal(raw, &hist)
	return total, hist, nil
}

func (s *Server) handleApprove(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	var body struct {
		Note string `json:"note"`
	}
	_ = decode(r, &body)
	id := r.PathValue("id")

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		writeErr(w, err)
		return
	}
	defer tx.Rollback(ctx)
	total, hist, err := s.lockPending(ctx, tx, id, u)
	if err != nil {
		writeErr(w, err)
		return
	}

	// An approver can only sign within their own authority; above it the
	// requisition escalates to the next approver who can.
	entry := approvalEntry{By: u.ID, ByName: u.Name, Note: body.Note, At: s.now()}
	if total > u.PerOrderLimit {
		chain, err := s.approverChain(ctx, tx, u.ID)
		if err != nil {
			writeErr(w, err)
			return
		}
		next, ok := governance.FirstApprover(chain, total)
		if !ok {
			writeErr(w, conflict("no approver above you has authority for this order"))
			return
		}
		entry.Action = "escalated"
		entry.Note = fmt.Sprintf("Approved up to $%.0f authority; escalated to %s. %s", u.PerOrderLimit, next.Name, body.Note)
		hist = append(hist, entry)
		raw, _ := json.Marshal(hist)
		if _, err := tx.Exec(ctx, `UPDATE oms.orders SET approver_id = $2, approvals = $3 WHERE id = $1`, id, next.ID, raw); err != nil {
			writeErr(w, err)
			return
		}
	} else {
		entry.Action = "approved"
		hist = append(hist, entry)
		raw, _ := json.Marshal(hist)
		if _, err := tx.Exec(ctx, `UPDATE oms.orders SET approvals = $2 WHERE id = $1`, id, raw); err != nil {
			writeErr(w, err)
			return
		}
		if err := s.release(ctx, tx, id); err != nil {
			writeErr(w, err)
			return
		}
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, err)
		return
	}
	s.handleGetOrder(w, r, u)
}

func (s *Server) handleReject(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	var body struct {
		Note string `json:"note"`
	}
	_ = decode(r, &body)
	id := r.PathValue("id")
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		writeErr(w, err)
		return
	}
	defer tx.Rollback(ctx)
	_, hist, err := s.lockPending(ctx, tx, id, u)
	if err != nil {
		writeErr(w, err)
		return
	}
	hist = append(hist, approvalEntry{By: u.ID, ByName: u.Name, Action: "rejected", Note: body.Note, At: s.now()})
	raw, _ := json.Marshal(hist)
	if _, err := tx.Exec(ctx, `UPDATE oms.orders SET status = 'rejected', approvals = $2 WHERE id = $1`, id, raw); err != nil {
		writeErr(w, err)
		return
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, err)
		return
	}
	s.handleGetOrder(w, r, u)
}

// handleShip simulates the warehouses picking and shipping a released order:
// reserved stock leaves on-hand, and KeepStock replenishment refills its bins.
func (s *Server) handleShip(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	id := r.PathValue("id")
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		writeErr(w, err)
		return
	}
	defer tx.Rollback(ctx)
	var status string
	if err := tx.QueryRow(ctx, `SELECT status FROM oms.orders WHERE id = $1 FOR UPDATE`, id).Scan(&status); err != nil {
		writeErr(w, err)
		return
	}
	if status != "released" {
		writeErr(w, conflict("only released orders can ship; order is "+status))
		return
	}
	if _, err := tx.Exec(ctx, `
		UPDATE oms.inventory i SET on_hand = i.on_hand - x.qty, reserved = i.reserved - x.qty
		FROM (SELECT s.node_id, sl.sku, sum(sl.qty) AS qty FROM oms.shipments s
		      JOIN oms.shipment_lines sl ON sl.shipment_id = s.id
		      WHERE s.order_id = $1 AND s.node_id IS NOT NULL AND s.status = 'released'
		      GROUP BY s.node_id, sl.sku) x
		WHERE i.node_id = x.node_id AND i.sku = x.sku`, id); err != nil {
		writeErr(w, err)
		return
	}
	// Backordered quantities are treated as arriving with the shipment in this simulation.
	if _, err := tx.Exec(ctx, `UPDATE oms.shipments SET status = 'shipped' WHERE order_id = $1`, id); err != nil {
		writeErr(w, err)
		return
	}
	if _, err := tx.Exec(ctx, `
		UPDATE keepstock.bins b SET on_hand = b.on_hand + l.qty, on_order = greatest(b.on_order - l.qty, 0)
		FROM oms.order_lines l WHERE l.order_id = $1 AND l.ref = 'bin:' || b.id`, id); err != nil {
		writeErr(w, err)
		return
	}
	if _, err := tx.Exec(ctx, `
		UPDATE keepstock.vending_slots v SET on_hand = least(v.on_hand + l.qty, v.capacity), on_order = greatest(v.on_order - l.qty, 0)
		FROM oms.order_lines l WHERE l.order_id = $1 AND l.ref = 'slot:' || v.machine_id || '/' || v.slot`, id); err != nil {
		writeErr(w, err)
		return
	}
	if _, err := tx.Exec(ctx, `UPDATE oms.orders SET status = 'shipped' WHERE id = $1`, id); err != nil {
		writeErr(w, err)
		return
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, err)
		return
	}
	s.handleGetOrder(w, r, u)
}
