package pricing

import (
	"testing"
	"time"
)

var today = time.Date(2026, 10, 23, 0, 0, 0, 0, time.UTC)

func TestListPriceWhenNoAgreements(t *testing.T) {
	r := Resolve(Input{ListPrice: 612, Qty: 2, Today: today})
	if r.UnitPrice != 612 || r.Source != "list" || r.Extended != 1224 {
		t.Fatalf("got %+v", r)
	}
}

func TestContractBeatsCategoryDiscount(t *testing.T) {
	r := Resolve(Input{
		ListPrice:        612,
		Qty:              1,
		Contract:         &Contract{Price: 539, Ref: "MSA-1", ValidTo: today.AddDate(1, 0, 0)},
		CategoryDiscount: &CategoryDiscount{CategoryID: "motors", CategoryName: "Motors", Pct: 8},
		Today:            today,
	})
	if r.Source != "contract" || r.UnitPrice != 539 {
		t.Fatalf("expected contract price, got %+v", r)
	}
	if len(r.Candidates) != 3 {
		t.Fatalf("expected 3 candidates, got %d", len(r.Candidates))
	}
}

func TestExpiredContractIsIgnored(t *testing.T) {
	r := Resolve(Input{
		ListPrice:        486,
		Qty:              1,
		Contract:         &Contract{Price: 470, Ref: "OLD", ValidTo: today.AddDate(0, -1, 0)},
		CategoryDiscount: &CategoryDiscount{CategoryName: "Motors", Pct: 2},
		Today:            today,
	})
	if r.Source != "category" || r.UnitPrice != 476.28 {
		t.Fatalf("expected category price 476.28, got %+v", r)
	}
	if r.Candidates[1].Applies || r.Candidates[1].Reason == "" {
		t.Fatalf("expired contract should be flagged, got %+v", r.Candidates[1])
	}
}

func TestVolumeTierWinsAtHighQuantity(t *testing.T) {
	tiers := []Tier{{MinQty: 10, Pct: 10}, {MinQty: 25, Pct: 20}, {MinQty: 100, Pct: 30}}
	disc := &CategoryDiscount{CategoryName: "Fasteners", Pct: 25}

	low := Resolve(Input{ListPrice: 10, Qty: 30, CategoryDiscount: disc, Tiers: tiers, Today: today})
	if low.Source != "category" || low.UnitPrice != 7.5 {
		t.Fatalf("at qty 30 the 25%% category discount should win, got %+v", low)
	}
	if low.NextTier == nil || low.NextTier.MinQty != 100 {
		t.Fatalf("expected next tier at 100, got %+v", low.NextTier)
	}

	high := Resolve(Input{ListPrice: 10, Qty: 100, CategoryDiscount: disc, Tiers: tiers, Today: today})
	if high.Source != "volume" || high.UnitPrice != 7 || high.Extended != 700 {
		t.Fatalf("at qty 100 the 30%% tier should win, got %+v", high)
	}
	if high.SavingsPct != 30 {
		t.Fatalf("savings pct = %v", high.SavingsPct)
	}
}

func TestRoundingToCents(t *testing.T) {
	r := Resolve(Input{ListPrice: 22.10, Qty: 3, CategoryDiscount: &CategoryDiscount{Pct: 10}, Today: today})
	if r.UnitPrice != 19.89 || r.Extended != 59.67 {
		t.Fatalf("got unit %v ext %v", r.UnitPrice, r.Extended)
	}
}
