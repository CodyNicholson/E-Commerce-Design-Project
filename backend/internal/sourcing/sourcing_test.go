package sourcing

import "testing"

// A miniature network around a Milwaukee plant.
var nodes = []Node{
	{ID: "dc-min", Kind: "dc", Name: "Minooka DC", Lat: 41.4553, Lon: -88.2620, HazmatCapable: true},
	{ID: "dc-kc", Kind: "dc", Name: "Kansas City DC", Lat: 39.0997, Lon: -94.5786, HazmatCapable: true},
	{ID: "br-mke", Kind: "branch", Name: "Milwaukee Branch", Lat: 43.0305, Lon: -87.95, WillCall: true},
	{ID: "sup", Kind: "supplier", Name: "Supplier", Lat: 35.38, Lon: -94.39, LeadDays: 5},
}

const plantLat, plantLon = 43.0389, -87.9065

func req(lines []Line, atp ATP, method, pref string) Request {
	return Request{Lines: lines, Nodes: nodes, ATP: atp, Lat: plantLat, Lon: plantLon, DeliveryMethod: method, Pref: pref}
}

func TestWillCallTakesBranchStockThenShipsRemainder(t *testing.T) {
	p := Build(req([]Line{{SKU: "MOTOR", Qty: 3}}, ATP{
		"br-mke": {"MOTOR": 2},
		"dc-min": {"MOTOR": 10},
	}, "will_call", "ship_available"))

	if len(p.Shipments) != 2 || !p.Split {
		t.Fatalf("expected split into 2 shipments, got %+v", p)
	}
	if p.Shipments[0].Method != "will_call" || p.Shipments[0].Lines[0].Qty != 2 {
		t.Fatalf("first shipment should be will-call of 2, got %+v", p.Shipments[0])
	}
	if p.Shipments[1].NodeID != "dc-min" || p.Shipments[1].Lines[0].Qty != 1 {
		t.Fatalf("remainder should ship from Minooka, got %+v", p.Shipments[1])
	}
}

func TestShipUsesCourierFromNearbyBranch(t *testing.T) {
	p := Build(req([]Line{{SKU: "BRG", Qty: 5}}, ATP{
		"br-mke": {"BRG": 30},
		"dc-min": {"BRG": 300},
	}, "ship", "ship_available"))
	if len(p.Shipments) != 1 || p.Shipments[0].Method != "courier" || p.Shipments[0].EtaDays != 0 {
		t.Fatalf("expected same-day courier from branch, got %+v", p.Shipments)
	}
}

func TestHazmatSkipsNonLicensedBranchAndCourier(t *testing.T) {
	p := Build(req([]Line{{SKU: "AEROSOL", Qty: 4, Hazmat: true}}, ATP{
		"br-mke": {"AEROSOL": 50},
		"dc-min": {"AEROSOL": 50},
	}, "will_call", "ship_available"))
	if len(p.Shipments) != 1 {
		t.Fatalf("expected one shipment, got %+v", p.Shipments)
	}
	s := p.Shipments[0]
	if s.NodeID != "dc-min" || s.Method != "hazmat_ground" {
		t.Fatalf("hazmat must ship ground from the licensed DC, got %+v", s)
	}
}

func TestSplitAcrossDCsAndBackorder(t *testing.T) {
	p := Build(req([]Line{{SKU: "WASHDOWN", Qty: 8}}, ATP{
		"dc-min": {"WASHDOWN": 1},
		"dc-kc":  {"WASHDOWN": 4},
	}, "ship", "ship_available"))
	if len(p.Shipments) != 2 {
		t.Fatalf("expected 2 shipments, got %+v", p.Shipments)
	}
	if len(p.Backorders) != 1 || p.Backorders[0].Qty != 3 {
		t.Fatalf("expected 3 backordered, got %+v", p.Backorders)
	}
	if p.Shipments[0].NodeID != "dc-min" {
		t.Fatalf("closest DC first, got %+v", p.Shipments)
	}
}

func TestShipCompletePrefersSingleNode(t *testing.T) {
	lines := []Line{{SKU: "A", Qty: 2}, {SKU: "B", Qty: 2}}
	atp := ATP{
		"br-mke": {"A": 5, "B": 0},
		"dc-min": {"A": 0, "B": 5},
		"dc-kc":  {"A": 5, "B": 5},
	}
	avail := Build(req(lines, atp, "ship", "ship_available"))
	if len(avail.Shipments) != 2 {
		t.Fatalf("ship-available should split, got %+v", avail.Shipments)
	}
	complete := Build(req(lines, atp, "ship", "ship_complete"))
	if len(complete.Shipments) != 1 || complete.Shipments[0].NodeID != "dc-kc" {
		t.Fatalf("ship-complete should consolidate at KC, got %+v", complete.Shipments)
	}
}

func TestShipCompleteHoldsWhenNoSingleNode(t *testing.T) {
	p := Build(req([]Line{{SKU: "A", Qty: 2}, {SKU: "B", Qty: 2}}, ATP{
		"br-mke": {"A": 5},
		"dc-kc":  {"B": 5},
	}, "ship", "ship_complete"))
	if !p.HeldForConsolidation || len(p.Shipments) != 2 {
		t.Fatalf("expected held consolidation across 2 shipments, got %+v", p)
	}
	if p.Shipments[0].EtaDays != p.Shipments[1].EtaDays || p.Shipments[0].Note == "" {
		t.Fatalf("held shipments should share the latest ETA, got %+v", p.Shipments)
	}
}

func TestDropShipOnlyGoesToSupplier(t *testing.T) {
	p := Build(req([]Line{{SKU: "BIG", Qty: 1, DropShipOnly: true}}, ATP{
		"dc-min": {"BIG": 10},
		"sup":    {"BIG": 3},
	}, "ship", "ship_complete"))
	if len(p.Shipments) != 1 || p.Shipments[0].Method != "drop_ship" {
		t.Fatalf("expected drop-ship, got %+v", p.Shipments)
	}
}

func TestDistance(t *testing.T) {
	d := DistanceMi(plantLat, plantLon, 41.4553, -88.2620)
	if d < 105 || d > 115 {
		t.Fatalf("Milwaukee to Minooka should be ~110 mi, got %v", d)
	}
}
