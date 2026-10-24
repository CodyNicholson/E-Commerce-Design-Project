package governance

import (
	"strings"
	"testing"
	"time"
)

var today = time.Date(2026, 10, 23, 0, 0, 0, 0, time.UTC)

var chain = []Approver{
	{ID: "u-marcus", Name: "Marcus", PerOrderLimit: 10000},
	{ID: "u-ruth", Name: "Ruth", PerOrderLimit: 100000},
}

func buyer() Buyer {
	return Buyer{ID: "u-dana", PerOrderLimit: 1500, MonthlyLimit: 10000, MTDSpend: 8800, Chain: chain}
}

func TestAutoApprovedWithinLimits(t *testing.T) {
	r := Evaluate(Input{Buyer: buyer(), Total: 900, Source: "web", Today: today})
	if r.Outcome != AutoApproved {
		t.Fatalf("got %+v", r)
	}
}

func TestMonthlyLimitRoutesToDirectApprover(t *testing.T) {
	r := Evaluate(Input{Buyer: buyer(), Total: 1400, Source: "web", Today: today})
	if r.Outcome != ApprovalRequired || r.Approver.ID != "u-marcus" {
		t.Fatalf("got %+v", r)
	}
}

func TestLargeOrderEscalatesPastDirectApprover(t *testing.T) {
	r := Evaluate(Input{Buyer: buyer(), Total: 25000, Source: "web", Today: today})
	if r.Outcome != ApprovalRequired || r.Approver.ID != "u-marcus" {
		t.Fatalf("expected routing to the direct approver first, got %+v", r)
	}
	last := r.Checks[len(r.Checks)-1]
	if !strings.Contains(last.Message, "escalates to Ruth") {
		t.Fatalf("expected escalation note, got %q", last.Message)
	}
	if a, _ := FirstApprover(chain, 25000); a.ID != "u-ruth" {
		t.Fatalf("FirstApprover should skip Marcus, got %+v", a)
	}
}

func TestNoApproverWithAuthorityBlocks(t *testing.T) {
	r := Evaluate(Input{Buyer: buyer(), Total: 250000, Source: "web", Today: today})
	if r.Outcome != Blocked {
		t.Fatalf("got %+v", r)
	}
}

func TestRestrictedCategoryNeedsCertification(t *testing.T) {
	lines := []Line{{SKU: "2YDW3", Restriction: &Restriction{CategoryName: "Safety Switches", Cert: "LICENSED_ELECTRICIAN"}}}
	r := Evaluate(Input{Buyer: buyer(), Lines: lines, Total: 100, Source: "web", Today: today})
	if r.Outcome != Blocked {
		t.Fatalf("uncertified buyer should be blocked, got %+v", r)
	}
	b := buyer()
	b.Certs = []string{"LICENSED_ELECTRICIAN"}
	r = Evaluate(Input{Buyer: b, Lines: lines, Total: 100, Source: "web", Today: today})
	if r.Outcome != AutoApproved {
		t.Fatalf("certified buyer should pass, got %+v", r)
	}
}

func TestBlanketPOChecks(t *testing.T) {
	bpo := &BlanketPO{Number: "BPO-1", FacilityID: "fac-mke", Authorized: 25000, Consumed: 24500,
		ValidFrom: today.AddDate(0, -9, 0), ValidTo: today.AddDate(0, 2, 0)}

	over := Evaluate(Input{Buyer: buyer(), Total: 600, FacilityID: "fac-mke", BlanketPO: bpo, Source: "web", Today: today})
	if over.Outcome != Blocked {
		t.Fatalf("over-consumed blanket PO should block, got %+v", over)
	}
	wrongFac := Evaluate(Input{Buyer: buyer(), Total: 100, FacilityID: "fac-ict", BlanketPO: bpo, Source: "web", Today: today})
	if wrongFac.Outcome != Blocked {
		t.Fatalf("other facility should block, got %+v", wrongFac)
	}
	expired := *bpo
	expired.ValidTo = today.AddDate(0, -1, 0)
	r := Evaluate(Input{Buyer: buyer(), Total: 100, FacilityID: "fac-mke", BlanketPO: &expired, Source: "web", Today: today})
	if r.Outcome != Blocked {
		t.Fatalf("expired blanket PO should block, got %+v", r)
	}
	ok := Evaluate(Input{Buyer: buyer(), Total: 100, FacilityID: "fac-mke", BlanketPO: bpo, Source: "web", Today: today})
	if ok.Outcome != AutoApproved {
		t.Fatalf("got %+v", ok)
	}
}

func TestEDISkipsSpendLimitsButNotRestrictions(t *testing.T) {
	r := Evaluate(Input{Buyer: buyer(), Total: 50000, Source: "edi", Today: today})
	if r.Outcome != AutoApproved {
		t.Fatalf("ERP-approved EDI order should not need approval, got %+v", r)
	}
	lines := []Line{{SKU: "9KL33", Restriction: &Restriction{CategoryName: "Chemicals", Cert: "HAZCOM_TRAINED"}}}
	r = Evaluate(Input{Buyer: buyer(), Lines: lines, Total: 50, Source: "edi", Today: today})
	if r.Outcome != Blocked {
		t.Fatalf("restrictions still apply to EDI, got %+v", r)
	}
}

func TestKeepStockReplenishmentIsPreAuthorized(t *testing.T) {
	lines := []Line{{SKU: "1BKR6", Restriction: &Restriction{CategoryName: "Breakers", Cert: "LICENSED_ELECTRICIAN"}}}
	r := Evaluate(Input{Buyer: buyer(), Lines: lines, Total: 99999, Source: "keepstock", Today: today})
	if r.Outcome != AutoApproved {
		t.Fatalf("got %+v", r)
	}
}
