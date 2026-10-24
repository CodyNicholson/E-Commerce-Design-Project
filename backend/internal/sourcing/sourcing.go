// Package sourcing is the OMS brain: it turns a cart into shipments.
//
// Inventory sits in three kinds of node: regional distribution centers (DCs),
// local branches (will-call pickup and same-day courier) and supplier
// drop-ship locations. Plan computes available-to-promise per node and
// allocates each line while honouring:
//   - hazmat lines only from nodes licensed to store them, never by courier
//   - drop-ship-only items only from suppliers
//   - will-call pickup at the nearest branch first
//   - ship-complete (one node or hold for consolidation) vs ship-available
//     (split freely, emergency partials, backorder the rest)
package sourcing

import (
	"fmt"
	"math"
	"sort"
)

const (
	CourierRadiusMi = 60
	// Ground transit is modelled as one business day per this many miles, plus one.
	groundMilesPerDay = 450
	backorderDays     = 10
)

type Node struct {
	ID            string  `json:"id"`
	Kind          string  `json:"kind"` // dc | branch | supplier
	Name          string  `json:"name"`
	Lat           float64 `json:"lat"`
	Lon           float64 `json:"lon"`
	HazmatCapable bool    `json:"hazmatCapable"`
	WillCall      bool    `json:"willCall"`
	LeadDays      int     `json:"leadDays"`
}

type Line struct {
	SKU          string
	Qty          int
	Hazmat       bool
	DropShipOnly bool
}

// ATP maps node ID -> SKU -> units available to promise (on hand minus reserved).
type ATP map[string]map[string]int

type Request struct {
	Lines          []Line
	Nodes          []Node
	ATP            ATP
	Lat, Lon       float64 // ship-to facility
	DeliveryMethod string  // ship | will_call
	Pref           string  // ship_available | ship_complete
}

type Allocation struct {
	SKU string `json:"sku"`
	Qty int    `json:"qty"`
}

type Shipment struct {
	NodeID     string       `json:"nodeId"`
	NodeName   string       `json:"nodeName"`
	NodeKind   string       `json:"nodeKind"`
	Method     string       `json:"method"` // will_call | courier | ground | hazmat_ground | drop_ship
	EtaDays    int          `json:"etaDays"`
	EtaLabel   string       `json:"etaLabel"`
	DistanceMi float64      `json:"distanceMi"`
	Lines      []Allocation `json:"lines"`
	Note       string       `json:"note,omitempty"`
}

type Plan struct {
	Shipments            []Shipment   `json:"shipments"`
	Backorders           []Allocation `json:"backorders"`
	BackorderEtaDays     int          `json:"backorderEtaDays"`
	Split                bool         `json:"split"`
	HeldForConsolidation bool         `json:"heldForConsolidation"`
	Notes                []string     `json:"notes"`
}

// groupKey merges allocations from the same node and method into one shipment.
type groupKey struct{ node, method string }

type candidate struct {
	node   Node
	dist   float64
	method string
	eta    int
}

// DistanceMi is the great-circle distance between two points.
func DistanceMi(lat1, lon1, lat2, lon2 float64) float64 {
	const r = 3958.8
	rad := math.Pi / 180
	dLat := (lat2 - lat1) * rad
	dLon := (lon2 - lon1) * rad
	a := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(lat1*rad)*math.Cos(lat2*rad)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * r * math.Asin(math.Sqrt(a))
}

func groundDays(dist float64) int { return 1 + int(dist/groundMilesPerDay) }

// candidatesFor lists, in preference order, the nodes (with method and ETA)
// that may fulfil a line.
func candidatesFor(l Line, req Request, dists map[string]float64) []candidate {
	var out []candidate
	if l.DropShipOnly {
		for _, n := range req.Nodes {
			if n.Kind == "supplier" && (!l.Hazmat || n.HazmatCapable) {
				out = append(out, candidate{n, dists[n.ID], "drop_ship", n.LeadDays + groundDays(dists[n.ID])})
			}
		}
		sortByEta(out)
		return out
	}

	if req.DeliveryMethod == "will_call" {
		if b := nearestWillCall(req.Nodes, dists); b != nil && (!l.Hazmat || b.HazmatCapable) {
			out = append(out, candidate{*b, dists[b.ID], "will_call", 0})
		}
	}

	var ship []candidate
	for _, n := range req.Nodes {
		if l.Hazmat && !n.HazmatCapable {
			continue
		}
		d := dists[n.ID]
		switch n.Kind {
		case "branch":
			if d > CourierRadiusMi {
				continue // branches only ship locally; their stock serves walk-in customers
			}
			if l.Hazmat {
				ship = append(ship, candidate{n, d, "hazmat_ground", 1})
			} else {
				ship = append(ship, candidate{n, d, "courier", 0})
			}
		case "dc":
			m := "ground"
			if l.Hazmat {
				m = "hazmat_ground"
			}
			ship = append(ship, candidate{n, d, m, groundDays(d)})
		}
	}
	sortByEta(ship)
	out = append(out, ship...)

	// Suppliers are the last resort before backorder.
	var sup []candidate
	for _, n := range req.Nodes {
		if n.Kind == "supplier" && (!l.Hazmat || n.HazmatCapable) {
			sup = append(sup, candidate{n, dists[n.ID], "drop_ship", n.LeadDays + groundDays(dists[n.ID])})
		}
	}
	sortByEta(sup)
	return append(out, sup...)
}

func sortByEta(c []candidate) {
	sort.SliceStable(c, func(i, j int) bool {
		if c[i].eta != c[j].eta {
			return c[i].eta < c[j].eta
		}
		return c[i].dist < c[j].dist
	})
}

func nearestWillCall(nodes []Node, dists map[string]float64) *Node {
	var best *Node
	for i := range nodes {
		n := nodes[i]
		if n.Kind == "branch" && n.WillCall && (best == nil || dists[n.ID] < dists[best.ID]) {
			best = &nodes[i]
		}
	}
	return best
}

func Build(req Request) Plan {
	dists := map[string]float64{}
	for _, n := range req.Nodes {
		dists[n.ID] = DistanceMi(req.Lat, req.Lon, n.Lat, n.Lon)
	}
	atp := ATP{}
	for node, skus := range req.ATP {
		atp[node] = map[string]int{}
		for s, q := range skus {
			atp[node][s] = q
		}
	}

	cands := make([][]candidate, len(req.Lines))
	for i, l := range req.Lines {
		cands[i] = candidatesFor(l, req, dists)
	}

	plan := Plan{Notes: []string{}, Backorders: []Allocation{}}
	groups := map[groupKey]*Shipment{}
	var order []groupKey
	add := func(c candidate, sku string, qty int) {
		k := groupKey{c.node.ID, c.method}
		s, ok := groups[k]
		if !ok {
			s = &Shipment{
				NodeID: c.node.ID, NodeName: c.node.Name, NodeKind: c.node.Kind,
				Method: c.method, EtaDays: c.eta, EtaLabel: etaLabel(c.method, c.eta),
				DistanceMi: math.Round(c.dist),
			}
			groups[k] = s
			order = append(order, k)
		}
		s.Lines = append(s.Lines, Allocation{sku, qty})
		atp[c.node.ID][sku] -= qty
	}

	if req.Pref == "ship_complete" && hasStockedLine(req.Lines) {
		if node, ok := singleNode(req, cands, atp); ok {
			for i, l := range req.Lines {
				if l.DropShipOnly {
					continue
				}
				for _, c := range cands[i] {
					if c.node.ID == node {
						add(c, l.SKU, l.Qty)
						break
					}
				}
			}
			plan.Notes = append(plan.Notes, "Ship complete: every stocked line fits in one node.")
			// Drop-ship-only lines can never consolidate; allocate them on their own.
			for i, l := range req.Lines {
				if l.DropShipOnly {
					allocateGreedy(l, cands[i], atp, add, &plan)
				}
			}
			return finish(plan, groups, order)
		}
		plan.HeldForConsolidation = true
		plan.Notes = append(plan.Notes, "Ship complete requested but no single node holds every line; lines are allocated across nodes and held so the order arrives together.")
	}

	for i, l := range req.Lines {
		allocateGreedy(l, cands[i], atp, add, &plan)
	}
	return finish(plan, groups, order)
}

func allocateGreedy(l Line, cands []candidate, atp ATP, add func(candidate, string, int), plan *Plan) {
	need := l.Qty
	for _, c := range cands {
		if need == 0 {
			break
		}
		avail := atp[c.node.ID][l.SKU]
		if avail <= 0 {
			continue
		}
		take := min(avail, need)
		add(c, l.SKU, take)
		need -= take
	}
	if need > 0 {
		plan.Backorders = append(plan.Backorders, Allocation{l.SKU, need})
	}
}

func hasStockedLine(lines []Line) bool {
	for _, l := range lines {
		if !l.DropShipOnly {
			return true
		}
	}
	return false
}

// singleNode returns the first preferred node that can fill every stocked line alone.
func singleNode(req Request, cands [][]candidate, atp ATP) (string, bool) {
	seen := map[string]bool{}
	for i, l := range req.Lines {
		if l.DropShipOnly {
			continue
		}
		for _, c := range cands[i] {
			if seen[c.node.ID] || c.node.Kind == "supplier" {
				continue
			}
			seen[c.node.ID] = true
			if nodeCovers(c.node.ID, req, cands, atp) {
				return c.node.ID, true
			}
		}
	}
	return "", false
}

func nodeCovers(node string, req Request, cands [][]candidate, atp ATP) bool {
	for i, l := range req.Lines {
		if l.DropShipOnly {
			continue
		}
		allowed := false
		for _, c := range cands[i] {
			if c.node.ID == node {
				allowed = true
				break
			}
		}
		if !allowed || atp[node][l.SKU] < l.Qty {
			return false
		}
	}
	return true
}

func finish(plan Plan, groups map[groupKey]*Shipment, order []groupKey) Plan {
	for _, k := range order {
		plan.Shipments = append(plan.Shipments, *groups[k])
	}
	sort.SliceStable(plan.Shipments, func(i, j int) bool { return plan.Shipments[i].EtaDays < plan.Shipments[j].EtaDays })

	if len(plan.Backorders) > 0 {
		plan.BackorderEtaDays = backorderDays
		plan.Notes = append(plan.Notes, fmt.Sprintf("%d line(s) backordered; expected in ~%d business days.", len(plan.Backorders), backorderDays))
	}
	if plan.HeldForConsolidation && len(plan.Shipments) > 1 {
		latest := 0
		for _, s := range plan.Shipments {
			latest = max(latest, s.EtaDays)
		}
		for i := range plan.Shipments {
			if plan.Shipments[i].Method != "will_call" && plan.Shipments[i].EtaDays < latest {
				plan.Shipments[i].Note = fmt.Sprintf("held %d day(s) to arrive with the rest of the order", latest-plan.Shipments[i].EtaDays)
				plan.Shipments[i].EtaDays = latest
				plan.Shipments[i].EtaLabel = etaLabel(plan.Shipments[i].Method, latest)
			}
		}
	}
	for _, s := range plan.Shipments {
		if s.Method == "hazmat_ground" {
			plan.Notes = append(plan.Notes, "Hazmat lines ship ground only (DOT 49 CFR) from hazmat-licensed nodes.")
			break
		}
	}
	plan.Split = len(plan.Shipments) > 1 || (len(plan.Shipments) > 0 && len(plan.Backorders) > 0)
	if plan.Shipments == nil {
		plan.Shipments = []Shipment{}
	}
	return plan
}

func etaLabel(method string, days int) string {
	switch method {
	case "will_call":
		return "Ready for pickup in ~1 hour"
	case "courier":
		return "Same-day courier"
	case "hazmat_ground":
		return fmt.Sprintf("Ground only (hazmat), %s", plural(days, "business day"))
	case "drop_ship":
		return fmt.Sprintf("Drop-ship from supplier, ~%s", plural(days, "business day"))
	default:
		return fmt.Sprintf("Ground, %s", plural(days, "business day"))
	}
}

func plural(n int, unit string) string {
	if n == 1 {
		return fmt.Sprintf("1 %s", unit)
	}
	return fmt.Sprintf("%d %ss", n, unit)
}
