package api

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/governance"
	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/pricing"
	"github.com/CodyNicholson/E-Commerce-Design-Project/backend/internal/sourcing"
)

// ---------------------------------------------------------------- categories

type attrDef struct {
	Key   string `json:"key"`
	Label string `json:"label"`
	Type  string `json:"type"`
	Unit  string `json:"unit,omitempty"`
	Facet bool   `json:"facet"`
}

type category struct {
	ID       string      `json:"id"`
	ParentID *string     `json:"parentId"`
	Name     string      `json:"name"`
	UNSPSC   string      `json:"unspsc"`
	Schema   []attrDef   `json:"attributeSchema"`
	Children []*category `json:"children,omitempty"`
}

type categories map[string]*category

func (s *Server) loadCategories(ctx context.Context, q dbtx) (categories, error) {
	list, err := collect(ctx, q, func(r pgx.Row, c **category) error {
		*c = &category{}
		x := *c
		var raw []byte
		if err := r.Scan(&x.ID, &x.ParentID, &x.Name, &x.UNSPSC, &raw); err != nil {
			return err
		}
		return json.Unmarshal(raw, &x.Schema)
	}, `SELECT id, parent_id, name, coalesce(unspsc, ''), attribute_schema FROM pim.categories ORDER BY name`)
	if err != nil {
		return nil, err
	}
	m := categories{}
	for _, c := range list {
		m[c.ID] = c
	}
	return m, nil
}

// lineage returns id followed by each ancestor up to the root.
func (cs categories) lineage(id string) []string {
	var out []string
	for c := cs[id]; c != nil; {
		out = append(out, c.ID)
		if c.ParentID == nil {
			break
		}
		c = cs[*c.ParentID]
	}
	return out
}

func (cs categories) descendants(id string) []string {
	out := []string{id}
	for _, c := range cs {
		if c.ID != id && slices.Contains(cs.lineage(c.ID), id) {
			out = append(out, c.ID)
		}
	}
	return out
}

func (cs categories) breadcrumb(id string) []map[string]string {
	ids := cs.lineage(id)
	out := make([]map[string]string, 0, len(ids))
	for i := len(ids) - 1; i >= 0; i-- {
		out = append(out, map[string]string{"id": ids[i], "name": cs[ids[i]].Name})
	}
	return out
}

func (s *Server) handleCategories(w http.ResponseWriter, r *http.Request) {
	cs, err := s.loadCategories(r.Context(), s.pool)
	if err != nil {
		writeErr(w, err)
		return
	}
	counts := map[string]int{}
	rows, err := collect(r.Context(), s.pool, func(r pgx.Row, x *struct {
		id string
		n  int
	}) error {
		return r.Scan(&x.id, &x.n)
	}, `SELECT category_id, count(*) FROM pim.products WHERE status = 'active' GROUP BY category_id`)
	if err != nil {
		writeErr(w, err)
		return
	}
	for _, x := range rows {
		for _, id := range cs.lineage(x.id) {
			counts[id] += x.n
		}
	}
	type node struct {
		*category
		Count    int     `json:"count"`
		Children []*node `json:"children"`
	}
	nodes := map[string]*node{}
	for id, c := range cs {
		nodes[id] = &node{category: c, Count: counts[id], Children: []*node{}}
	}
	roots := []*node{}
	for _, n := range nodes {
		if n.ParentID == nil {
			roots = append(roots, n)
		} else {
			p := nodes[*n.ParentID]
			p.Children = append(p.Children, n)
		}
	}
	sortNodes := func(ns []*node) { sort.Slice(ns, func(i, j int) bool { return ns[i].Name < ns[j].Name }) }
	sortNodes(roots)
	for _, n := range nodes {
		sortNodes(n.Children)
	}
	writeJSON(w, http.StatusOK, roots)
}

// ---------------------------------------------------------------- products

type product struct {
	SKU            string         `json:"sku"`
	CategoryID     string         `json:"categoryId"`
	Name           string         `json:"name"`
	Brand          string         `json:"brand"`
	MfrPartNumber  string         `json:"mfrPartNumber"`
	Description    string         `json:"description"`
	ListPrice      float64        `json:"listPrice"`
	UOM            string         `json:"uom"`
	WeightLb       float64        `json:"weightLb"`
	Attributes     map[string]any `json:"attributes"`
	HazmatUN       *string        `json:"hazmatUn"`
	HazmatClass    *string        `json:"hazmatClass"`
	SDSURL         *string        `json:"sdsUrl"`
	DropShipOnly   bool           `json:"dropShipOnly"`
	Status         string         `json:"status"`
	ReplacementSKU *string        `json:"replacementSku"`
}

func (p *product) hazmat() bool { return p.HazmatUN != nil }

const productCols = `sku, category_id, name, brand, mfr_part_number, description, list_price, uom, weight_lb,
	attributes, hazmat_un, hazmat_class, sds_url, drop_ship_only, status, replacement_sku`

func scanProduct(r pgx.Row, pp **product) error {
	p := &product{}
	*pp = p
	return r.Scan(&p.SKU, &p.CategoryID, &p.Name, &p.Brand, &p.MfrPartNumber, &p.Description, &p.ListPrice, &p.UOM,
		&p.WeightLb, &p.Attributes, &p.HazmatUN, &p.HazmatClass, &p.SDSURL, &p.DropShipOnly, &p.Status, &p.ReplacementSKU)
}

func (s *Server) loadProducts(ctx context.Context, q dbtx, skus []string) (map[string]*product, error) {
	list, err := collect(ctx, q, scanProduct, `SELECT `+productCols+` FROM pim.products WHERE sku = ANY($1)`, skus)
	if err != nil {
		return nil, err
	}
	m := map[string]*product{}
	for _, p := range list {
		m[p.SKU] = p
	}
	return m, nil
}

var nonAlnum = regexp.MustCompile(`[^A-Za-z0-9]`)

// normalizePart makes "6205-2RS1", "6205 2rs1" and "62052RS1" compare equal.
func normalizePart(s string) string { return strings.ToUpper(nonAlnum.ReplaceAllString(s, "")) }

// ---------------------------------------------------------------- pricing

// priceBook holds one corporation's commercial agreements so many lines can be priced in memory.
type priceBook struct {
	cats         categories
	contracts    map[string]pricing.Contract
	discounts    map[string]float64
	tiers        map[string][]pricing.Tier
	restrictions map[string]string
	today        time.Time
}

func (s *Server) loadPriceBook(ctx context.Context, q dbtx, corpID string) (*priceBook, error) {
	cats, err := s.loadCategories(ctx, q)
	if err != nil {
		return nil, err
	}
	b := &priceBook{cats: cats, contracts: map[string]pricing.Contract{}, discounts: map[string]float64{},
		tiers: map[string][]pricing.Tier{}, restrictions: map[string]string{}, today: s.today()}

	type contractRow struct {
		sku string
		c   pricing.Contract
	}
	contracts, err := collect(ctx, q, func(r pgx.Row, x *contractRow) error {
		return r.Scan(&x.sku, &x.c.Price, &x.c.Ref, &x.c.ValidTo)
	}, `SELECT sku, price, contract_ref, valid_to FROM pricing.contract_prices WHERE corporation_id = $1`, corpID)
	if err != nil {
		return nil, err
	}
	for _, x := range contracts {
		b.contracts[x.sku] = x.c
	}

	type pctRow struct {
		cat string
		pct float64
	}
	discounts, err := collect(ctx, q, func(r pgx.Row, x *pctRow) error { return r.Scan(&x.cat, &x.pct) },
		`SELECT category_id, discount_pct FROM pricing.category_discounts WHERE corporation_id = $1`, corpID)
	if err != nil {
		return nil, err
	}
	for _, x := range discounts {
		b.discounts[x.cat] = x.pct
	}

	tiers, err := collect(ctx, q, func(r pgx.Row, t *pricing.Tier) error { return r.Scan(&t.CategoryID, &t.MinQty, &t.Pct) },
		`SELECT category_id, min_qty, discount_pct FROM pricing.volume_tiers ORDER BY min_qty`)
	if err != nil {
		return nil, err
	}
	for _, t := range tiers {
		b.tiers[t.CategoryID] = append(b.tiers[t.CategoryID], t)
	}

	type certRow struct{ cat, cert string }
	restr, err := collect(ctx, q, func(r pgx.Row, x *certRow) error { return r.Scan(&x.cat, &x.cert) },
		`SELECT category_id, required_cert FROM accounts.category_restrictions WHERE corporation_id = $1`, corpID)
	if err != nil {
		return nil, err
	}
	for _, x := range restr {
		b.restrictions[x.cat] = x.cert
	}
	return b, nil
}

// tiersFor returns the volume breaks of the most specific category that defines any.
func (b *priceBook) tiersFor(catID string) []pricing.Tier {
	for _, id := range b.cats.lineage(catID) {
		if t, ok := b.tiers[id]; ok {
			return t
		}
	}
	return nil
}

func (b *priceBook) quote(p *product, qty int) pricing.Result {
	in := pricing.Input{ListPrice: p.ListPrice, Qty: qty, Tiers: b.tiersFor(p.CategoryID), Today: b.today}
	if c, ok := b.contracts[p.SKU]; ok {
		in.Contract = &c
	}
	for _, id := range b.cats.lineage(p.CategoryID) {
		if pct, ok := b.discounts[id]; ok {
			in.CategoryDiscount = &pricing.CategoryDiscount{CategoryID: id, CategoryName: b.cats[id].Name, Pct: pct}
			break
		}
	}
	return pricing.Resolve(in)
}

func (b *priceBook) restriction(catID string) *governance.Restriction {
	for _, id := range b.cats.lineage(catID) {
		if cert, ok := b.restrictions[id]; ok {
			return &governance.Restriction{CategoryName: b.cats[id].Name, Cert: cert}
		}
	}
	return nil
}

// ---------------------------------------------------------------- inventory

func (s *Server) loadNodes(ctx context.Context, q dbtx) ([]sourcing.Node, error) {
	return collect(ctx, q, func(r pgx.Row, n *sourcing.Node) error {
		return r.Scan(&n.ID, &n.Kind, &n.Name, &n.Lat, &n.Lon, &n.HazmatCapable, &n.WillCall, &n.LeadDays)
	}, `SELECT id, kind, name, lat, lon, hazmat_capable, will_call, lead_days FROM oms.nodes ORDER BY id`)
}

// loadATP returns available-to-promise (on hand minus reserved) per node for the SKUs.
// forUpdate locks the rows so a concurrent release cannot promise the same units.
func (s *Server) loadATP(ctx context.Context, q dbtx, skus []string, forUpdate bool) (sourcing.ATP, error) {
	sql := `SELECT node_id, sku, on_hand - reserved FROM oms.inventory WHERE sku = ANY($1)`
	if forUpdate {
		sql += ` ORDER BY node_id, sku FOR UPDATE`
	}
	type row struct {
		node, sku string
		atp       int
	}
	rows, err := collect(ctx, q, func(r pgx.Row, x *row) error { return r.Scan(&x.node, &x.sku, &x.atp) }, sql, skus)
	if err != nil {
		return nil, err
	}
	atp := sourcing.ATP{}
	for _, x := range rows {
		if atp[x.node] == nil {
			atp[x.node] = map[string]int{}
		}
		atp[x.node][x.sku] = x.atp
	}
	return atp, nil
}

type facility struct {
	ID, Name, Code, State, CorporationID string
	Lat, Lon                             float64
}

func (s *Server) loadFacility(ctx context.Context, q dbtx, id string) (*facility, error) {
	f := &facility{}
	err := q.QueryRow(ctx, `
		WITH RECURSIVE up AS (
			SELECT id, parent_id, kind FROM accounts.org_units WHERE id = $1
			UNION ALL SELECT o.id, o.parent_id, o.kind FROM accounts.org_units o JOIN up ON o.id = up.parent_id
		)
		SELECT f.id, f.name, f.code, coalesce(f.state, ''), coalesce(f.lat, 0), coalesce(f.lon, 0),
		       (SELECT id FROM up WHERE kind = 'corporation')
		FROM accounts.org_units f WHERE f.id = $1 AND f.kind = 'facility'`, id).
		Scan(&f.ID, &f.Name, &f.Code, &f.State, &f.Lat, &f.Lon, &f.CorporationID)
	return f, err
}

type availability struct {
	Network     int    `json:"network"`
	LocalBranch int    `json:"localBranch"`
	BranchName  string `json:"branchName"`
}

// availabilityFor summarises stock for search results: whole network and the buyer's nearest branch.
func availabilityFor(atp sourcing.ATP, nodes []sourcing.Node, f *facility, sku string) availability {
	var a availability
	best := math.MaxFloat64
	for _, n := range nodes {
		if n.Kind == "supplier" {
			continue
		}
		q := max(atp[n.ID][sku], 0)
		a.Network += q
		if n.Kind == "branch" && f != nil {
			if d := sourcing.DistanceMi(f.Lat, f.Lon, n.Lat, n.Lon); d < best {
				best, a.LocalBranch, a.BranchName = d, q, n.Name
			}
		}
	}
	return a
}

// ---------------------------------------------------------------- search

type searchItem struct {
	*product
	CategoryName string         `json:"categoryName"`
	Price        pricing.Result `json:"price"`
	Availability availability   `json:"availability"`
}

type facetValue struct {
	Value    string `json:"value"`
	Count    int    `json:"count"`
	Selected bool   `json:"selected"`
}

type facet struct {
	attrDef
	Values []facetValue `json:"values"`
}

func attrString(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(x)
	default:
		return fmt.Sprint(x)
	}
}

func (s *Server) handleSearch(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	qs := r.URL.Query()
	text := strings.TrimSpace(qs.Get("q"))
	catID := qs.Get("category")

	book, err := s.loadPriceBook(ctx, s.pool, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}
	cats := book.cats
	if catID != "" && cats[catID] == nil {
		writeErr(w, badRequest("unknown category "+catID))
		return
	}

	sql := `SELECT ` + productCols + ` FROM pim.products WHERE status = 'active'`
	args := []any{}
	if catID != "" {
		args = append(args, cats.descendants(catID))
		sql += fmt.Sprintf(` AND category_id = ANY($%d)`, len(args))
	}
	for _, tok := range strings.Fields(text) {
		args = append(args, tok)
		sql += fmt.Sprintf(` AND (sku || ' ' || name || ' ' || brand || ' ' || mfr_part_number || ' ' || description) ILIKE '%%' || $%d || '%%'`, len(args))
	}
	sql += ` ORDER BY category_id, list_price`
	matched, err := collect(ctx, s.pool, scanProduct, sql, args...)
	if err != nil {
		writeErr(w, err)
		return
	}

	crossRefs, err := s.resolveCrossRefs(ctx, text)
	if err != nil {
		writeErr(w, err)
		return
	}

	// Category facet counts come from the text match alone.
	catCounts := map[string]int{}
	for _, p := range matched {
		catCounts[p.CategoryID]++
	}
	type catFacet struct {
		ID    string `json:"id"`
		Name  string `json:"name"`
		Count int    `json:"count"`
	}
	categoryFacets := []catFacet{}
	for id, n := range catCounts {
		categoryFacets = append(categoryFacets, catFacet{id, cats[id].Name, n})
	}
	sort.Slice(categoryFacets, func(i, j int) bool { return categoryFacets[i].Count > categoryFacets[j].Count })

	// Technical facets are only meaningful inside one category's schema.
	var defs []attrDef
	if catID != "" {
		seen := map[string]bool{}
		for _, id := range append([]string{catID}, cats.descendants(catID)...) {
			for _, d := range cats[id].Schema {
				if d.Facet && !seen[d.Key] {
					seen[d.Key] = true
					defs = append(defs, d)
				}
			}
		}
	}
	selected := map[string][]string{}
	for k, v := range qs {
		if key, ok := strings.CutPrefix(k, "f."); ok && len(v) > 0 {
			selected[key] = v
		}
	}
	matches := func(p *product, skip string) bool {
		for key, vals := range selected {
			if key != skip && !slices.Contains(vals, attrString(p.Attributes[key])) {
				return false
			}
		}
		return true
	}

	// Disjunctive faceting: each facet counts products that pass every *other* filter.
	facets := []facet{}
	for _, d := range defs {
		counts := map[string]int{}
		for _, p := range matched {
			if v, ok := p.Attributes[d.Key]; ok && matches(p, d.Key) {
				counts[attrString(v)]++
			}
		}
		if len(counts) == 0 {
			continue
		}
		f := facet{attrDef: d}
		for v, n := range counts {
			f.Values = append(f.Values, facetValue{v, n, slices.Contains(selected[d.Key], v)})
		}
		sort.Slice(f.Values, func(i, j int) bool {
			a, errA := strconv.ParseFloat(f.Values[i].Value, 64)
			b, errB := strconv.ParseFloat(f.Values[j].Value, 64)
			if errA == nil && errB == nil {
				return a < b
			}
			return f.Values[i].Value < f.Values[j].Value
		})
		facets = append(facets, f)
	}

	var results []*product
	for _, p := range matched {
		if matches(p, "") {
			results = append(results, p)
		}
	}

	skus := make([]string, len(results))
	for i, p := range results {
		skus[i] = p.SKU
	}
	atp, err := s.loadATP(ctx, s.pool, skus, false)
	if err != nil {
		writeErr(w, err)
		return
	}
	nodes, err := s.loadNodes(ctx, s.pool)
	if err != nil {
		writeErr(w, err)
		return
	}
	fac, _ := s.loadFacility(ctx, s.pool, u.FacilityID)

	items := []searchItem{}
	for _, p := range results {
		items = append(items, searchItem{p, cats[p.CategoryID].Name, book.quote(p, 1), availabilityFor(atp, nodes, fac, p.SKU)})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"query": text, "category": catID, "total": len(items), "items": items,
		"facets": facets, "categoryFacets": categoryFacets, "crossRefs": crossRefs,
	})
}

type crossRefHit struct {
	RefType       string `json:"refType"`
	RefBrand      string `json:"refBrand"`
	RefPartNumber string `json:"refPartNumber"`
	Note          string `json:"note"`
	SKU           string `json:"sku"`
	Name          string `json:"name"`
	Brand         string `json:"brand"`
}

// resolveCrossRefs maps a competitor, OEM, obsolete or UPC number to our SKU.
func (s *Server) resolveCrossRefs(ctx context.Context, text string) ([]crossRefHit, error) {
	norm := normalizePart(text)
	if len(norm) < 3 {
		return []crossRefHit{}, nil
	}
	return collect(ctx, s.pool, func(r pgx.Row, h *crossRefHit) error {
		return r.Scan(&h.RefType, &h.RefBrand, &h.RefPartNumber, &h.Note, &h.SKU, &h.Name, &h.Brand)
	}, `
		SELECT x.ref_type, x.ref_brand, x.ref_part_number, x.note, p.sku, p.name, p.brand
		FROM pim.cross_references x JOIN pim.products p ON p.sku = x.sku
		WHERE x.ref_norm = $1
		UNION ALL
		SELECT 'mfr', p.brand, p.mfr_part_number, 'Manufacturer part number', p.sku, p.name, p.brand
		FROM pim.products p
		WHERE upper(regexp_replace(p.mfr_part_number, '[^A-Za-z0-9]', '', 'g')) = $1 AND p.status = 'active'`, norm)
}

// ---------------------------------------------------------------- product detail

func (s *Server) handleProduct(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	sku := strings.ToUpper(r.PathValue("sku"))
	qty, _ := strconv.Atoi(r.URL.Query().Get("qty"))
	qty = max(qty, 1)

	prods, err := s.loadProducts(ctx, s.pool, []string{sku})
	if err != nil {
		writeErr(w, err)
		return
	}
	p := prods[sku]
	if p == nil {
		writeErr(w, notFound("no product "+sku))
		return
	}
	book, err := s.loadPriceBook(ctx, s.pool, u.CorporationID)
	if err != nil {
		writeErr(w, err)
		return
	}
	cat := book.cats[p.CategoryID]

	xrefs, err := collect(ctx, s.pool, func(r pgx.Row, h *crossRefHit) error {
		return r.Scan(&h.RefType, &h.RefBrand, &h.RefPartNumber, &h.Note)
	}, `SELECT ref_type, ref_brand, ref_part_number, note FROM pim.cross_references WHERE sku = $1 ORDER BY ref_type, ref_brand`, sku)
	if err != nil {
		writeErr(w, err)
		return
	}

	equivalents, err := collect(ctx, s.pool, scanProduct, `SELECT `+productCols+` FROM pim.products
		WHERE category_id = $1 AND sku <> $2 AND status = 'active' AND attributes = $3::jsonb`, p.CategoryID, sku, p.Attributes)
	if err != nil {
		writeErr(w, err)
		return
	}
	type alt struct {
		*product
		Price pricing.Result `json:"price"`
	}
	alts := []alt{}
	for _, e := range equivalents {
		alts = append(alts, alt{e, book.quote(e, qty)})
	}
	var replacement *alt
	if p.ReplacementSKU != nil {
		rp, err := s.loadProducts(ctx, s.pool, []string{*p.ReplacementSKU})
		if err != nil {
			writeErr(w, err)
			return
		}
		if x := rp[*p.ReplacementSKU]; x != nil {
			replacement = &alt{x, book.quote(x, qty)}
		}
	}

	// Inventory by node, nearest first.
	atp, err := s.loadATP(ctx, s.pool, []string{sku}, false)
	if err != nil {
		writeErr(w, err)
		return
	}
	nodes, err := s.loadNodes(ctx, s.pool)
	if err != nil {
		writeErr(w, err)
		return
	}
	fac, err := s.loadFacility(ctx, s.pool, u.FacilityID)
	if err != nil {
		writeErr(w, err)
		return
	}
	type nodeStock struct {
		sourcing.Node
		ATP        int     `json:"atp"`
		DistanceMi float64 `json:"distanceMi"`
		Stocked    bool    `json:"stocked"`
	}
	stock := []nodeStock{}
	for _, n := range nodes {
		q, ok := atp[n.ID][sku]
		stock = append(stock, nodeStock{n, max(q, 0), math.Round(sourcing.DistanceMi(fac.Lat, fac.Lon, n.Lat, n.Lon)), ok})
	}
	sort.Slice(stock, func(i, j int) bool { return stock[i].DistanceMi < stock[j].DistanceMi })

	// The same SKU through every corporation's price book: the heart of contract pricing.
	type corpPrice struct {
		CorporationID string         `json:"corporationId"`
		Corporation   string         `json:"corporation"`
		Price         pricing.Result `json:"price"`
	}
	corps, err := collect(ctx, s.pool, func(r pgx.Row, c *corpPrice) error { return r.Scan(&c.CorporationID, &c.Corporation) },
		`SELECT id, name FROM accounts.org_units WHERE kind = 'corporation' ORDER BY name`)
	if err != nil {
		writeErr(w, err)
		return
	}
	for i := range corps {
		b, err := s.loadPriceBook(ctx, s.pool, corps[i].CorporationID)
		if err != nil {
			writeErr(w, err)
			return
		}
		corps[i].Price = b.quote(p, qty)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"product": p, "category": cat, "breadcrumb": book.cats.breadcrumb(p.CategoryID),
		"price": book.quote(p, qty), "tiers": book.tiersFor(p.CategoryID), "restriction": book.restriction(p.CategoryID),
		"crossRefs": xrefs, "equivalents": alts, "replacement": replacement, "inventory": stock,
		"priceByCustomer": corps, "facility": map[string]string{"id": fac.ID, "name": fac.Name},
	})
}

// ---------------------------------------------------------------- network

func (s *Server) handleNetwork(w http.ResponseWriter, r *http.Request, u *user) {
	ctx := r.Context()
	nodes, err := s.loadNodes(ctx, s.pool)
	if err != nil {
		writeErr(w, err)
		return
	}
	fac, err := s.loadFacility(ctx, s.pool, u.FacilityID)
	if err != nil {
		writeErr(w, err)
		return
	}
	type summary struct {
		node, city, state  string
		skus, units, resvd int
	}
	sums, err := collect(ctx, s.pool, func(r pgx.Row, x *summary) error {
		return r.Scan(&x.node, &x.city, &x.state, &x.skus, &x.units, &x.resvd)
	}, `SELECT n.id, n.city, n.state, count(i.sku) FILTER (WHERE i.on_hand > 0), coalesce(sum(i.on_hand), 0), coalesce(sum(i.reserved), 0)
		FROM oms.nodes n LEFT JOIN oms.inventory i ON i.node_id = n.id GROUP BY n.id`)
	if err != nil {
		writeErr(w, err)
		return
	}
	byID := map[string]summary{}
	for _, x := range sums {
		byID[x.node] = x
	}
	type nodeOut struct {
		sourcing.Node
		City       string  `json:"city"`
		State      string  `json:"state"`
		SKUs       int     `json:"skus"`
		Units      int     `json:"units"`
		Reserved   int     `json:"reserved"`
		DistanceMi float64 `json:"distanceMi"`
	}
	out := []nodeOut{}
	for _, n := range nodes {
		x := byID[n.ID]
		out = append(out, nodeOut{n, x.city, x.state, x.skus, x.units, x.resvd, math.Round(sourcing.DistanceMi(fac.Lat, fac.Lon, n.Lat, n.Lon))})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].DistanceMi < out[j].DistanceMi })
	writeJSON(w, http.StatusOK, map[string]any{
		"facility": map[string]any{"id": fac.ID, "name": fac.Name, "state": fac.State, "lat": fac.Lat, "lon": fac.Lon},
		"nodes":    out, "courierRadiusMi": sourcing.CourierRadiusMi,
	})
}
