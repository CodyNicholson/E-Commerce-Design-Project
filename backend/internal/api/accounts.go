package api

import (
	"context"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/governance"
)

type user struct {
	ID            string   `json:"id"`
	Name          string   `json:"name"`
	Email         string   `json:"email"`
	Title         string   `json:"title"`
	Role          string   `json:"role"`
	CorporationID string   `json:"corporationId"`
	Corporation   string   `json:"corporation"`
	FacilityID    string   `json:"facilityId"`
	CostCenterID  string   `json:"costCenterId"`
	PerOrderLimit float64  `json:"perOrderLimit"`
	MonthlyLimit  float64  `json:"monthlyLimit"`
	ApproverID    *string  `json:"approverId"`
	Certs         []string `json:"certifications"`
	BadgeID       *string  `json:"badgeId"`
}

const userCols = `u.id, u.name, u.email, u.title, u.role, u.corporation_id, c.name, coalesce(u.facility_id, ''),
	coalesce(u.cost_center_id, ''), u.per_order_limit, u.monthly_limit, u.approver_id, u.certifications, u.badge_id`

func scanUser(row interface{ Scan(...any) error }) (*user, error) {
	var u user
	err := row.Scan(&u.ID, &u.Name, &u.Email, &u.Title, &u.Role, &u.CorporationID, &u.Corporation, &u.FacilityID,
		&u.CostCenterID, &u.PerOrderLimit, &u.MonthlyLimit, &u.ApproverID, &u.Certs, &u.BadgeID)
	return &u, err
}

func (s *Server) loadUser(ctx context.Context, q dbtx, id string) (*user, error) {
	return scanUser(q.QueryRow(ctx, `SELECT `+userCols+` FROM accounts.users u
		JOIN accounts.org_units c ON c.id = u.corporation_id WHERE u.id = $1`, id))
}

// approverChain walks approver_id upward: direct approver first.
func (s *Server) approverChain(ctx context.Context, q dbtx, userID string) ([]governance.Approver, error) {
	return collect(ctx, q, func(r pgx.Row, a *governance.Approver) error {
		return r.Scan(&a.ID, &a.Name, &a.Title, &a.PerOrderLimit)
	}, `
		WITH RECURSIVE chain AS (
			SELECT a.id, a.name, a.title, a.per_order_limit, a.approver_id, 1 AS depth
			FROM accounts.users u JOIN accounts.users a ON a.id = u.approver_id WHERE u.id = $1
			UNION ALL
			SELECT a.id, a.name, a.title, a.per_order_limit, a.approver_id, c.depth + 1
			FROM chain c JOIN accounts.users a ON a.id = c.approver_id WHERE c.depth < 10
		)
		SELECT id, name, title, per_order_limit FROM chain ORDER BY depth`, userID)
}

// mtdSpend is the buyer's month-to-date committed spend (everything not rejected).
func (s *Server) mtdSpend(ctx context.Context, q dbtx, userID string) (float64, error) {
	var v float64
	err := q.QueryRow(ctx, `SELECT coalesce(sum(total), 0)::float8 FROM oms.orders
		WHERE user_id = $1 AND status <> 'rejected' AND created_at >= date_trunc('month', now())`, userID).Scan(&v)
	return v, err
}

type orgUnit struct {
	ID       string     `json:"id"`
	ParentID *string    `json:"parentId"`
	Kind     string     `json:"kind"`
	Name     string     `json:"name"`
	Code     string     `json:"code"`
	State    *string    `json:"state"`
	City     *string    `json:"city"`
	Lat      *float64   `json:"lat,omitempty"`
	Lon      *float64   `json:"lon,omitempty"`
	Children []*orgUnit `json:"children,omitempty"`
}

func (s *Server) loadOrgUnits(ctx context.Context, q dbtx, corpID string) ([]*orgUnit, error) {
	return collect(ctx, q, func(r pgx.Row, o **orgUnit) error {
		*o = &orgUnit{}
		x := *o
		return r.Scan(&x.ID, &x.ParentID, &x.Kind, &x.Name, &x.Code, &x.State, &x.City, &x.Lat, &x.Lon)
	}, `
		WITH RECURSIVE tree AS (
			SELECT * FROM accounts.org_units WHERE id = $1
			UNION ALL
			SELECT o.* FROM accounts.org_units o JOIN tree t ON o.parent_id = t.id
		)
		SELECT id, parent_id, kind, name, code, state, city, lat, lon FROM tree ORDER BY code`, corpID)
}

func (s *Server) handleUsers(w http.ResponseWriter, r *http.Request) {
	users, err := collect(r.Context(), s.pool, func(r pgx.Row, x **user) error {
		var err error
		*x, err = scanUser(r)
		return err
	}, `SELECT `+userCols+` FROM accounts.users u
		JOIN accounts.org_units c ON c.id = u.corporation_id ORDER BY c.name, u.per_order_limit`)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, users)
}

type blanketPO struct {
	ID         string    `json:"id"`
	FacilityID *string   `json:"facilityId"`
	PONumber   string    `json:"poNumber"`
	Authorized float64   `json:"authorized"`
	Consumed   float64   `json:"consumed"`
	ValidFrom  time.Time `json:"validFrom"`
	ValidTo    time.Time `json:"validTo"`
}

func (s *Server) loadBlanketPOs(ctx context.Context, q dbtx, corpID string) ([]blanketPO, error) {
	return collect(ctx, q, func(r pgx.Row, b *blanketPO) error {
		return r.Scan(&b.ID, &b.FacilityID, &b.PONumber, &b.Authorized, &b.Consumed, &b.ValidFrom, &b.ValidTo)
	}, `SELECT id, facility_id, po_number, amount_authorized, amount_consumed, valid_from, valid_to
		FROM accounts.blanket_pos WHERE corporation_id = $1 ORDER BY po_number`, corpID)
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	chain, err := s.approverChain(ctx, s.pool, u.ID)
	if err != nil {
		writeErr(w, err)
		return
	}
	mtd, err := s.mtdSpend(ctx, s.pool, u.ID)
	if err != nil {
		writeErr(w, err)
		return
	}
	units, err := s.loadOrgUnits(ctx, s.pool, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}
	bpos, err := s.loadBlanketPOs(ctx, s.pool, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}
	var pending int
	if err := s.pool.QueryRow(ctx, `SELECT count(*) FROM oms.orders WHERE approver_id = $1 AND status = 'pending_approval'`, u.ID).Scan(&pending); err != nil {
		writeErr(w, err)
		return
	}
	// Cost centers the buyer may charge: every cost center in the corporation, grouped by facility.
	byID := map[string]*orgUnit{}
	for _, o := range units {
		byID[o.ID] = o
	}
	type ccOption struct {
		ID         string `json:"id"`
		Name       string `json:"name"`
		Code       string `json:"code"`
		FacilityID string `json:"facilityId"`
		Facility   string `json:"facility"`
		State      string `json:"state"`
	}
	ccs := []ccOption{}
	for _, o := range units {
		if o.Kind == "cost_center" && o.ParentID != nil {
			f := byID[*o.ParentID]
			st := ""
			if f.State != nil {
				st = *f.State
			}
			ccs = append(ccs, ccOption{o.ID, o.Name, o.Code, f.ID, f.Name, st})
		}
	}
	path := []string{}
	for id := u.CostCenterID; id != ""; {
		o := byID[id]
		if o == nil {
			break
		}
		path = append([]string{o.Name}, path...)
		if o.ParentID == nil {
			break
		}
		id = *o.ParentID
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"user": u, "approverChain": chain, "mtdSpend": mtd, "costCenters": ccs, "orgPath": path,
		"blanketPOs": bpos, "pendingApprovals": pending,
	})
}

func (s *Server) handleAccount(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	units, err := s.loadOrgUnits(ctx, s.pool, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}
	byID := map[string]*orgUnit{}
	var root *orgUnit
	for _, o := range units {
		byID[o.ID] = o
	}
	for _, o := range units {
		if o.ParentID == nil {
			root = o
		} else if p := byID[*o.ParentID]; p != nil {
			p.Children = append(p.Children, o)
		}
	}

	type userRow struct {
		*user
		MTDSpend float64 `json:"mtdSpend"`
	}
	users, err := collect(ctx, s.pool, func(r pgx.Row, x *userRow) error {
		var err error
		x.user, err = scanUser(r)
		return err
	}, `SELECT `+userCols+` FROM accounts.users u
		JOIN accounts.org_units c ON c.id = u.corporation_id WHERE u.corporation_id = $1 ORDER BY u.per_order_limit DESC`, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}
	for i := range users {
		if users[i].MTDSpend, err = s.mtdSpend(ctx, s.pool, users[i].ID); err != nil {
			writeErr(w, err)
			return
		}
	}

	bpos, err := s.loadBlanketPOs(ctx, s.pool, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}

	type exemption struct {
		State       string    `json:"state"`
		Certificate string    `json:"certificate"`
		Type        string    `json:"type"`
		ExpiresOn   time.Time `json:"expiresOn"`
		Valid       bool      `json:"valid"`
	}
	exemptions, err := collect(ctx, s.pool, func(r pgx.Row, e *exemption) error {
		err := r.Scan(&e.State, &e.Certificate, &e.Type, &e.ExpiresOn)
		e.Valid = !s.today().After(e.ExpiresOn)
		return err
	}, `SELECT state, certificate_number, exemption_type, expires_on FROM accounts.tax_exemptions
		WHERE corporation_id = $1 ORDER BY state`, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}

	type restriction struct {
		CategoryID string `json:"categoryId"`
		Category   string `json:"category"`
		Cert       string `json:"cert"`
	}
	restrictions, err := collect(ctx, s.pool, func(r pgx.Row, x *restriction) error {
		return r.Scan(&x.CategoryID, &x.Category, &x.Cert)
	}, `SELECT r.category_id, c.name, r.required_cert FROM accounts.category_restrictions r
		JOIN pim.categories c ON c.id = r.category_id WHERE r.corporation_id = $1`, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}

	type contract struct {
		SKU       string    `json:"sku"`
		Name      string    `json:"name"`
		ListPrice float64   `json:"listPrice"`
		Price     float64   `json:"price"`
		Ref       string    `json:"ref"`
		ValidTo   time.Time `json:"validTo"`
	}
	contracts, err := collect(ctx, s.pool, func(r pgx.Row, c *contract) error {
		return r.Scan(&c.SKU, &c.Name, &c.ListPrice, &c.Price, &c.Ref, &c.ValidTo)
	}, `SELECT cp.sku, p.name, p.list_price, cp.price, cp.contract_ref, cp.valid_to
		FROM pricing.contract_prices cp JOIN pim.products p ON p.sku = cp.sku WHERE cp.corporation_id = $1 ORDER BY cp.sku`, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}

	type discount struct {
		CategoryID string  `json:"categoryId"`
		Category   string  `json:"category"`
		Pct        float64 `json:"pct"`
	}
	discounts, err := collect(ctx, s.pool, func(r pgx.Row, d *discount) error {
		return r.Scan(&d.CategoryID, &d.Category, &d.Pct)
	}, `SELECT d.category_id, c.name, d.discount_pct FROM pricing.category_discounts d
		JOIN pim.categories c ON c.id = d.category_id WHERE d.corporation_id = $1 ORDER BY d.discount_pct DESC`, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"tree": root, "users": users, "blanketPOs": bpos, "taxExemptions": exemptions,
		"restrictions": restrictions, "contractPrices": contracts, "categoryDiscounts": discounts,
	})
}
