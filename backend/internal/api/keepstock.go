package api

import (
	"context"
	"fmt"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"
)

// KeepStock is vendor-managed inventory inside the customer's plant:
// barcode-labelled bins that are cycle-counted, and badge-swipe vending
// machines. Either one dropping to its reorder point raises a replenishment
// order automatically, charged to the owning cost center.

type bin struct {
	ID            string     `json:"id"`
	FacilityID    string     `json:"facilityId"`
	CostCenterID  string     `json:"costCenterId"`
	CostCenter    string     `json:"costCenter"`
	SKU           string     `json:"sku"`
	Name          string     `json:"name"`
	Location      string     `json:"location"`
	MinQty        int        `json:"minQty"`
	MaxQty        int        `json:"maxQty"`
	OnHand        int        `json:"onHand"`
	OnOrder       int        `json:"onOrder"`
	LastScannedAt *time.Time `json:"lastScannedAt"`
}

const binSQL = `SELECT b.id, b.facility_id, b.cost_center_id, cc.code || ' ' || cc.name, b.sku, p.name, b.location,
	b.min_qty, b.max_qty, b.on_hand, b.on_order, b.last_scanned_at
	FROM keepstock.bins b JOIN pim.products p ON p.sku = b.sku JOIN accounts.org_units cc ON cc.id = b.cost_center_id`

func scanBin(r pgx.Row, b *bin) error {
	return r.Scan(&b.ID, &b.FacilityID, &b.CostCenterID, &b.CostCenter, &b.SKU, &b.Name, &b.Location,
		&b.MinQty, &b.MaxQty, &b.OnHand, &b.OnOrder, &b.LastScannedAt)
}

type slot struct {
	MachineID  string `json:"machineId"`
	Slot       string `json:"slot"`
	SKU        string `json:"sku"`
	Name       string `json:"name"`
	OnHand     int    `json:"onHand"`
	Capacity   int    `json:"capacity"`
	Par        int    `json:"par"`
	OnOrder    int    `json:"onOrder"`
	DailyLimit int    `json:"dailyLimit"`
}

const slotSQL = `SELECT v.machine_id, v.slot, v.sku, p.name, v.on_hand, v.capacity, v.par, v.on_order, v.daily_limit
	FROM keepstock.vending_slots v JOIN pim.products p ON p.sku = v.sku`

func scanSlot(r pgx.Row, s *slot) error {
	return r.Scan(&s.MachineID, &s.Slot, &s.SKU, &s.Name, &s.OnHand, &s.Capacity, &s.Par, &s.OnOrder, &s.DailyLimit)
}

// facilityInCorp guards KeepStock actions to facilities of the acting user's corporation.
func (s *Server) facilityInCorp(ctx context.Context, q dbtx, facilityID string, u *user) error {
	f, err := s.loadFacility(ctx, q, facilityID)
	if err == pgx.ErrNoRows {
		return notFound("no facility " + facilityID)
	} else if err != nil {
		return err
	}
	if f.CorporationID != u.CorporationID {
		return forbidden("facility belongs to another corporation")
	}
	return nil
}

func (s *Server) handleKeepStock(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	facID := r.URL.Query().Get("facilityId")
	if facID == "" {
		facID = u.FacilityID
	}
	if err := s.facilityInCorp(ctx, s.pool, facID, u); err != nil {
		writeErr(w, err)
		return
	}
	type fac struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	}
	facilities, err := collect(ctx, s.pool, func(r pgx.Row, f *fac) error { return r.Scan(&f.ID, &f.Name) }, `
		SELECT f.id, f.name FROM accounts.org_units f
		WHERE f.kind = 'facility' AND f.parent_id IN (SELECT id FROM accounts.org_units WHERE parent_id = $1)
		  AND (EXISTS (SELECT 1 FROM keepstock.bins b WHERE b.facility_id = f.id)
		    OR EXISTS (SELECT 1 FROM keepstock.vending_machines m WHERE m.facility_id = f.id))
		ORDER BY f.name`, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}
	bins, err := collect(ctx, s.pool, scanBin, binSQL+` WHERE b.facility_id = $1 ORDER BY b.id`, facID)
	if err != nil {
		writeErr(w, err)
		return
	}
	type machine struct {
		ID         string `json:"id"`
		Name       string `json:"name"`
		Location   string `json:"location"`
		CostCenter string `json:"costCenter"`
		Slots      []slot `json:"slots"`
	}
	machines, err := collect(ctx, s.pool, func(r pgx.Row, m *machine) error { return r.Scan(&m.ID, &m.Name, &m.Location, &m.CostCenter) },
		`SELECT m.id, m.name, m.location, cc.code || ' ' || cc.name FROM keepstock.vending_machines m
		 JOIN accounts.org_units cc ON cc.id = m.cost_center_id WHERE m.facility_id = $1 ORDER BY m.id`, facID)
	if err != nil {
		writeErr(w, err)
		return
	}
	for i := range machines {
		if machines[i].Slots, err = collect(ctx, s.pool, scanSlot, slotSQL+` WHERE v.machine_id = $1 ORDER BY v.slot`, machines[i].ID); err != nil {
			writeErr(w, err)
			return
		}
	}
	type event struct {
		ID        int       `json:"id"`
		MachineID string    `json:"machineId"`
		Slot      string    `json:"slot"`
		BadgeID   string    `json:"badgeId"`
		UserName  *string   `json:"userName"`
		SKU       string    `json:"sku"`
		Qty       int       `json:"qty"`
		Outcome   string    `json:"outcome"`
		Message   string    `json:"message"`
		CreatedAt time.Time `json:"createdAt"`
	}
	events, err := collect(ctx, s.pool, func(r pgx.Row, e *event) error {
		return r.Scan(&e.ID, &e.MachineID, &e.Slot, &e.BadgeID, &e.UserName, &e.SKU, &e.Qty, &e.Outcome, &e.Message, &e.CreatedAt)
	}, `SELECT e.id, e.machine_id, e.slot, e.badge_id, u.name, e.sku, e.qty, e.outcome, e.message, e.created_at
		FROM keepstock.vending_events e JOIN keepstock.vending_machines m ON m.id = e.machine_id
		LEFT JOIN accounts.users u ON u.id = e.user_id WHERE m.facility_id = $1 ORDER BY e.id DESC LIMIT 25`, facID)
	if err != nil {
		writeErr(w, err)
		return
	}
	orders, err := collect(ctx, s.pool, scanOrderSummary, orderSummarySQL+` WHERE o.source = 'keepstock' AND o.facility_id = $1
		ORDER BY o.created_at DESC LIMIT 15`, facID)
	if err != nil {
		writeErr(w, err)
		return
	}
	type badge struct {
		BadgeID string `json:"badgeId"`
		Name    string `json:"name"`
	}
	badges, err := collect(ctx, s.pool, func(r pgx.Row, b *badge) error { return r.Scan(&b.BadgeID, &b.Name) },
		`SELECT badge_id, name FROM accounts.users WHERE badge_id IS NOT NULL ORDER BY badge_id`)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"facilityId": facID, "facilities": facilities, "bins": bins, "machines": machines,
		"events": events, "orders": orders, "badges": badges,
	})
}

type replenishResult struct {
	OrderID    string  `json:"orderId"`
	CostCenter string  `json:"costCenter"`
	Lines      int     `json:"lines"`
	Total      float64 `json:"total"`
	Shipments  int     `json:"shipments"`
}

// replenish raises one KeepStock order for a cost center and marks the bins/slots as on order.
func (s *Server) replenish(ctx context.Context, tx pgx.Tx, u *user, costCenterID string, lines []lineReq) (*replenishResult, error) {
	id, ev, err := s.placeOrderTx(ctx, tx, u, orderReq{Lines: lines, CostCenterID: costCenterID, PONumber: "KEEPSTOCK-AUTO",
		DeliveryMethod: "ship", FulfillmentPref: "ship_available", Source: "keepstock"})
	if err != nil {
		return nil, err
	}
	for _, l := range lines {
		if _, err := tx.Exec(ctx, `UPDATE keepstock.bins SET on_order = on_order + $2 WHERE 'bin:' || id = $1`, l.Ref, l.Qty); err != nil {
			return nil, err
		}
		if _, err := tx.Exec(ctx, `UPDATE keepstock.vending_slots SET on_order = on_order + $2 WHERE 'slot:' || machine_id || '/' || slot = $1`, l.Ref, l.Qty); err != nil {
			return nil, err
		}
	}
	return &replenishResult{OrderID: id, CostCenter: ev.CostCenter["code"] + " " + ev.CostCenter["name"], Lines: len(lines),
		Total: ev.Total, Shipments: len(ev.Plan.Shipments)}, nil
}

func (s *Server) handleBinScan(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	var body struct {
		OnHand *int `json:"onHand"`
	}
	if err := decode(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	if body.OnHand == nil || *body.OnHand < 0 {
		writeErr(w, badRequest("onHand must be a non-negative count"))
		return
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		writeErr(w, err)
		return
	}
	defer tx.Rollback(ctx)
	var b bin
	if err := scanBin(tx.QueryRow(ctx, binSQL+` WHERE b.id = $1 FOR UPDATE OF b`, r.PathValue("id")), &b); err != nil {
		writeErr(w, err)
		return
	}
	if err := s.facilityInCorp(ctx, tx, b.FacilityID, u); err != nil {
		writeErr(w, err)
		return
	}
	b.OnHand = *body.OnHand
	if _, err := tx.Exec(ctx, `UPDATE keepstock.bins SET on_hand = $2, last_scanned_at = now() WHERE id = $1`, b.ID, b.OnHand); err != nil {
		writeErr(w, err)
		return
	}
	var order *replenishResult
	msg := fmt.Sprintf("Counted %d; reorder point is %d.", b.OnHand, b.MinQty)
	switch {
	case b.OnHand <= b.MinQty && b.OnOrder == 0:
		qty := b.MaxQty - b.OnHand
		if order, err = s.replenish(ctx, tx, u, b.CostCenterID, []lineReq{{SKU: b.SKU, Qty: qty, Ref: "bin:" + b.ID}}); err != nil {
			writeErr(w, err)
			return
		}
		b.OnOrder = qty
		msg += fmt.Sprintf(" At or below min: replenishment %s raised for %d to restore max %d.", order.OrderID, qty, b.MaxQty)
	case b.OnHand <= b.MinQty:
		msg += fmt.Sprintf(" Already %d on order; no duplicate replenishment.", b.OnOrder)
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"bin": b, "order": order, "message": msg})
}

func (s *Server) handleDispense(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	var body struct {
		Slot    string `json:"slot"`
		BadgeID string `json:"badgeId"`
		Qty     int    `json:"qty"`
	}
	if err := decode(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	body.Qty = max(body.Qty, 1)
	machineID := r.PathValue("id")

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		writeErr(w, err)
		return
	}
	defer tx.Rollback(ctx)
	var sl slot
	if err := scanSlot(tx.QueryRow(ctx, slotSQL+` WHERE v.machine_id = $1 AND v.slot = $2 FOR UPDATE OF v`, machineID, body.Slot), &sl); err != nil {
		writeErr(w, err)
		return
	}
	var facID, machineCorp string
	if err := tx.QueryRow(ctx, `SELECT facility_id FROM keepstock.vending_machines WHERE id = $1`, machineID).Scan(&facID); err != nil {
		writeErr(w, err)
		return
	}
	f, err := s.loadFacility(ctx, tx, facID)
	if err != nil {
		writeErr(w, err)
		return
	}
	machineCorp = f.CorporationID

	// The badge identifies the employee, whose cost center is charged.
	var badgeUser *user
	var userID *string
	outcome, msg := "dispensed", ""
	var used int
	var badgeOwner string
	switch err := tx.QueryRow(ctx, `SELECT id FROM accounts.users WHERE badge_id = $1`, body.BadgeID).Scan(&badgeOwner); err {
	case nil:
		if badgeUser, err = s.loadUser(ctx, tx, badgeOwner); err != nil {
			writeErr(w, err)
			return
		}
		userID = &badgeUser.ID
	case pgx.ErrNoRows:
	default:
		writeErr(w, err)
		return
	}
	switch {
	case badgeUser == nil:
		outcome, msg = "denied", "Badge not recognised."
	case badgeUser.CorporationID != machineCorp:
		outcome, msg = "denied", "Badge belongs to another company."
	default:
		if err := tx.QueryRow(ctx, `SELECT coalesce(sum(qty), 0) FROM keepstock.vending_events
			WHERE badge_id = $1 AND machine_id = $2 AND slot = $3 AND outcome = 'dispensed' AND created_at >= date_trunc('day', now())`,
			body.BadgeID, machineID, body.Slot).Scan(&used); err != nil {
			writeErr(w, err)
			return
		}
		switch {
		case used+body.Qty > sl.DailyLimit:
			outcome, msg = "denied", fmt.Sprintf("Daily limit is %d per person; %s already took %d today.", sl.DailyLimit, badgeUser.Name, used)
		case sl.OnHand < body.Qty:
			outcome, msg = "denied", "Slot is empty."
		default:
			var cc string
			if err := tx.QueryRow(ctx, `SELECT code || ' ' || name FROM accounts.org_units WHERE id = $1`, badgeUser.CostCenterID).Scan(&cc); err != nil {
				writeErr(w, err)
				return
			}
			sl.OnHand -= body.Qty
			msg = fmt.Sprintf("Dispensed %d x %s to %s; charged to cost center %s.", body.Qty, sl.SKU, badgeUser.Name, cc)
			if _, err := tx.Exec(ctx, `UPDATE keepstock.vending_slots SET on_hand = $3 WHERE machine_id = $1 AND slot = $2`, machineID, body.Slot, sl.OnHand); err != nil {
				writeErr(w, err)
				return
			}
		}
	}

	var order *replenishResult
	if outcome == "dispensed" && sl.OnHand <= sl.Par && sl.OnOrder == 0 {
		var cc string
		if err := tx.QueryRow(ctx, `SELECT cost_center_id FROM keepstock.vending_machines WHERE id = $1`, machineID).Scan(&cc); err != nil {
			writeErr(w, err)
			return
		}
		qty := sl.Capacity - sl.OnHand
		if order, err = s.replenish(ctx, tx, badgeUser, cc, []lineReq{{SKU: sl.SKU, Qty: qty, Ref: "slot:" + machineID + "/" + sl.Slot}}); err != nil {
			writeErr(w, err)
			return
		}
		sl.OnOrder = qty
		msg += fmt.Sprintf(" Slot reached par %d: replenishment %s raised for %d.", sl.Par, order.OrderID, qty)
	}
	if _, err := tx.Exec(ctx, `INSERT INTO keepstock.vending_events (machine_id, slot, badge_id, user_id, sku, qty, outcome, message)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`, machineID, body.Slot, body.BadgeID, userID, sl.SKU, body.Qty, outcome, msg); err != nil {
		writeErr(w, err)
		return
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"outcome": outcome, "message": msg, "slot": sl, "order": order})
}

// handleReplenish sweeps every bin and slot at or below its reorder point
// and raises one consolidated order per cost center.
func (s *Server) handleReplenish(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	var body struct {
		FacilityID string `json:"facilityId"`
	}
	if err := decode(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		writeErr(w, err)
		return
	}
	defer tx.Rollback(ctx)
	if err := s.facilityInCorp(ctx, tx, body.FacilityID, u); err != nil {
		writeErr(w, err)
		return
	}
	type need struct {
		cc   string
		line lineReq
	}
	needs, err := collect(ctx, tx, func(r pgx.Row, n *need) error { return r.Scan(&n.cc, &n.line.SKU, &n.line.Qty, &n.line.Ref) }, `
		SELECT cost_center_id, sku, max_qty - on_hand, 'bin:' || id FROM keepstock.bins
		WHERE facility_id = $1 AND on_hand <= min_qty AND on_order = 0
		UNION ALL
		SELECT m.cost_center_id, v.sku, v.capacity - v.on_hand, 'slot:' || v.machine_id || '/' || v.slot
		FROM keepstock.vending_slots v JOIN keepstock.vending_machines m ON m.id = v.machine_id
		WHERE m.facility_id = $1 AND v.on_hand <= v.par AND v.on_order = 0
		ORDER BY 1, 4`, body.FacilityID)
	if err != nil {
		writeErr(w, err)
		return
	}
	byCC := map[string][]lineReq{}
	var order []string
	for _, n := range needs {
		if _, ok := byCC[n.cc]; !ok {
			order = append(order, n.cc)
		}
		byCC[n.cc] = append(byCC[n.cc], n.line)
	}
	results := []*replenishResult{}
	for _, cc := range order {
		res, err := s.replenish(ctx, tx, u, cc, byCC[cc])
		if err != nil {
			writeErr(w, err)
			return
		}
		results = append(results, res)
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"orders": results, "linesConsidered": len(needs)})
}
