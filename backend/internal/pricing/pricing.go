// Package pricing resolves the net price one customer pays for one SKU.
//
// B2B distributors rarely have "a price". The list price is only the starting
// point; the buyer's corporation may hold a negotiated contract price for the
// SKU, a percentage discount on the whole category, and everyone gets volume
// breaks. Resolve evaluates every candidate and the lowest one wins
// ("best price"), returning the full trace so the UI can explain why.
package pricing

import (
	"fmt"
	"math"
	"time"
)

// Contract is a customer-specific fixed net price for a SKU.
type Contract struct {
	Price   float64
	Ref     string
	ValidTo time.Time
}

// CategoryDiscount is a customer-specific % off list for a category subtree.
type CategoryDiscount struct {
	CategoryID   string
	CategoryName string
	Pct          float64
}

// Tier is a volume break: buying at least MinQty earns Pct off list.
type Tier struct {
	CategoryID string  `json:"categoryId"`
	MinQty     int     `json:"minQty"`
	Pct        float64 `json:"pct"`
}

type Input struct {
	ListPrice float64
	Qty       int
	Contract  *Contract
	// CategoryDiscount is the most specific discount found walking up the category tree.
	CategoryDiscount *CategoryDiscount
	// Tiers holds the volume breaks of the most specific category that has any.
	Tiers []Tier
	Today time.Time
}

type Candidate struct {
	Source    string  `json:"source"` // list | contract | category | volume
	Label     string  `json:"label"`
	UnitPrice float64 `json:"unitPrice"`
	Applies   bool    `json:"applies"`
	Reason    string  `json:"reason,omitempty"`
}

type Result struct {
	ListPrice  float64     `json:"listPrice"`
	UnitPrice  float64     `json:"unitPrice"`
	Qty        int         `json:"qty"`
	Extended   float64     `json:"extended"`
	Source     string      `json:"source"`
	Label      string      `json:"label"`
	SavingsPct float64     `json:"savingsPct"`
	Candidates []Candidate `json:"candidates"`
	NextTier   *Tier       `json:"nextTier,omitempty"`
}

// Round2 rounds a currency amount to cents, half away from zero.
func Round2(v float64) float64 {
	return math.Round(v*100) / 100
}

func Resolve(in Input) Result {
	qty := in.Qty
	if qty < 1 {
		qty = 1
	}
	list := Round2(in.ListPrice)
	cands := []Candidate{{Source: "list", Label: "List price", UnitPrice: list, Applies: true}}

	if c := in.Contract; c != nil {
		cand := Candidate{Source: "contract", Label: "Contract " + c.Ref, UnitPrice: Round2(c.Price), Applies: true}
		if !c.ValidTo.IsZero() && in.Today.After(c.ValidTo) {
			cand.Applies = false
			cand.Reason = "expired " + c.ValidTo.Format("2006-01-02")
		}
		cands = append(cands, cand)
	}

	if d := in.CategoryDiscount; d != nil && d.Pct > 0 {
		cands = append(cands, Candidate{
			Source:    "category",
			Label:     fmt.Sprintf("Category discount %s%% (%s)", fmtPct(d.Pct), d.CategoryName),
			UnitPrice: Round2(list * (1 - d.Pct/100)),
			Applies:   true,
		})
	}

	var best *Tier
	var next *Tier
	for i := range in.Tiers {
		t := in.Tiers[i]
		if qty >= t.MinQty {
			if best == nil || t.Pct > best.Pct {
				best = &t
			}
		} else if next == nil || t.MinQty < next.MinQty {
			next = &t
		}
	}
	if best != nil {
		cands = append(cands, Candidate{
			Source:    "volume",
			Label:     fmt.Sprintf("Volume tier %s%% (%d+)", fmtPct(best.Pct), best.MinQty),
			UnitPrice: Round2(list * (1 - best.Pct/100)),
			Applies:   true,
		})
	}

	win := 0
	for i, c := range cands {
		if c.Applies && c.UnitPrice < cands[win].UnitPrice {
			win = i
		}
	}
	unit := cands[win].UnitPrice
	savings := 0.0
	if list > 0 {
		savings = math.Round((1-unit/list)*1000) / 10
	}
	return Result{
		ListPrice:  list,
		UnitPrice:  unit,
		Qty:        qty,
		Extended:   Round2(unit * float64(qty)),
		Source:     cands[win].Source,
		Label:      cands[win].Label,
		SavingsPct: savings,
		Candidates: cands,
		NextTier:   next,
	}
}

func fmtPct(p float64) string {
	if p == math.Trunc(p) {
		return fmt.Sprintf("%.0f", p)
	}
	return fmt.Sprintf("%.1f", p)
}
