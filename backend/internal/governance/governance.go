// Package governance decides whether a corporate buyer may place an order.
//
// In B2B the customer is a hierarchy, not a person. The buyer's own spend
// limits, the certifications the corporation demands for restricted
// categories, the blanket PO being charged and the approval chain all have a
// say. Evaluate returns every check it ran, plus the outcome:
// auto_approved, approval_required (with the approver) or blocked.
package governance

import (
	"fmt"
	"slices"
	"time"
)

const (
	AutoApproved     = "auto_approved"
	ApprovalRequired = "approval_required"
	Blocked          = "blocked"
)

type Approver struct {
	ID            string  `json:"id"`
	Name          string  `json:"name"`
	Title         string  `json:"title"`
	PerOrderLimit float64 `json:"perOrderLimit"`
}

type Buyer struct {
	ID            string
	PerOrderLimit float64
	MonthlyLimit  float64
	MTDSpend      float64
	Certs         []string
	// Chain is the approval chain, direct approver first.
	Chain []Approver
}

type Restriction struct {
	CategoryName string `json:"categoryName"`
	Cert         string `json:"cert"`
}

type Line struct {
	SKU          string
	Name         string
	Discontinued bool
	Restriction  *Restriction
}

type BlanketPO struct {
	Number     string
	FacilityID string
	Authorized float64
	Consumed   float64
	ValidFrom  time.Time
	ValidTo    time.Time
}

type Input struct {
	Buyer      Buyer
	Lines      []Line
	Total      float64
	FacilityID string
	PONumber   string
	BlanketPO  *BlanketPO
	// Source: web | punchout | edi | keepstock. EDI orders were already approved in the
	// buyer's ERP and KeepStock replenishment is pre-authorized, so spend limits are skipped.
	Source string
	Today  time.Time
}

type Check struct {
	Code    string `json:"code"`
	Level   string `json:"level"` // pass | info | approval | block
	Message string `json:"message"`
}

type Result struct {
	Outcome  string    `json:"outcome"`
	Checks   []Check   `json:"checks"`
	Approver *Approver `json:"approver,omitempty"`
}

func Evaluate(in Input) Result {
	var checks []Check
	add := func(code, level, format string, args ...any) {
		checks = append(checks, Check{code, level, fmt.Sprintf(format, args...)})
	}

	for _, l := range in.Lines {
		if in.Source == "keepstock" {
			break // the VMI agreement pre-approved every SKU stocked in the program
		}
		if l.Discontinued {
			add("discontinued", "block", "%s is discontinued; order its replacement instead.", l.SKU)
		}
		if r := l.Restriction; r != nil {
			if slices.Contains(in.Buyer.Certs, r.Cert) {
				add("restricted_category", "pass", "%s (%s) requires %s; buyer holds it.", l.SKU, r.CategoryName, r.Cert)
			} else {
				add("restricted_category", "block", "%s (%s) requires %s certification, which this buyer does not hold.", l.SKU, r.CategoryName, r.Cert)
			}
		}
	}

	if b := in.BlanketPO; b != nil {
		remaining := b.Authorized - b.Consumed
		switch {
		case in.Today.Before(b.ValidFrom) || in.Today.After(b.ValidTo):
			add("blanket_po", "block", "Blanket PO %s is only valid %s to %s.", b.Number, b.ValidFrom.Format("2006-01-02"), b.ValidTo.Format("2006-01-02"))
		case b.FacilityID != "" && b.FacilityID != in.FacilityID:
			add("blanket_po", "block", "Blanket PO %s belongs to a different facility.", b.Number)
		case in.Total > remaining:
			add("blanket_po", "block", "Blanket PO %s has $%.2f remaining; order total is $%.2f.", b.Number, remaining, in.Total)
		default:
			add("blanket_po", "pass", "Charged to blanket PO %s ($%.2f remaining after this order).", b.Number, remaining-in.Total)
		}
	} else if in.PONumber == "" && in.Source == "web" {
		add("po_number", "info", "No PO number entered; the order will be invoiced to the cost center.")
	}

	needsApproval := false
	switch in.Source {
	case "edi":
		add("erp_approved", "info", "Approved in the buyer's ERP before transmission; spend limits are enforced there.")
	case "keepstock":
		add("keepstock_program", "info", "KeepStock replenishment is pre-authorized by the VMI program agreement (items, quantities and spend).")
	default:
		if in.Total > in.Buyer.PerOrderLimit {
			needsApproval = true
			add("per_order_limit", "approval", "Order total $%.2f exceeds the buyer's per-order limit of $%.2f.", in.Total, in.Buyer.PerOrderLimit)
		} else {
			add("per_order_limit", "pass", "Within per-order limit of $%.2f.", in.Buyer.PerOrderLimit)
		}
		if projected := in.Buyer.MTDSpend + in.Total; projected > in.Buyer.MonthlyLimit {
			needsApproval = true
			add("monthly_limit", "approval", "Month-to-date spend would reach $%.2f, over the monthly limit of $%.2f.", projected, in.Buyer.MonthlyLimit)
		} else {
			add("monthly_limit", "pass", "Month-to-date spend after this order: $%.2f of $%.2f.", projected, in.Buyer.MonthlyLimit)
		}
	}

	res := Result{Checks: checks, Outcome: AutoApproved}
	for _, c := range checks {
		if c.Level == "block" {
			res.Outcome = Blocked
			return res
		}
	}
	if needsApproval {
		final, ok := FirstApprover(in.Buyer.Chain, in.Total)
		if !ok {
			res.Checks = append(res.Checks, Check{"approval_chain", "block", fmt.Sprintf("No approver in the chain has authority for $%.2f.", in.Total)})
			res.Outcome = Blocked
			return res
		}
		// The requisition always starts with the direct approver, who escalates
		// it if the total is beyond their own authority.
		direct := in.Buyer.Chain[0]
		res.Outcome = ApprovalRequired
		res.Approver = &direct
		msg := fmt.Sprintf("Routed to %s (%s, authority $%.0f).", direct.Name, direct.Title, direct.PerOrderLimit)
		if final.ID != direct.ID {
			msg += fmt.Sprintf(" Total exceeds that authority, so it escalates to %s (%s, authority $%.0f).", final.Name, final.Title, final.PerOrderLimit)
		}
		res.Checks = append(res.Checks, Check{"approval_chain", "approval", msg})
	}
	return res
}

// FirstApprover walks up the chain and returns the first approver whose
// per-order authority covers total; approvers with too little authority are skipped.
func FirstApprover(chain []Approver, total float64) (Approver, bool) {
	for _, a := range chain {
		if a.PerOrderLimit >= total {
			return a, true
		}
	}
	return Approver{}, false
}
