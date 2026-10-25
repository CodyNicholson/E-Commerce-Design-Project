package com.example.mromirror.governance;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    static final List<Governance.Approver> CHAIN = List.of(
            new Governance.Approver("u-marcus", "Marcus", "", 10000),
            new Governance.Approver("u-ruth", "Ruth", "", 100000));

    static Governance.Buyer buyer() {
        return buyer(List.of());
    }

    static Governance.Buyer buyer(List<String> certs) {
        return new Governance.Buyer("u-dana", 1500, 10000, 8800, certs, CHAIN);
    }

    static Governance.Result eval(Governance.Buyer b, List<Governance.Line> lines, double total, String facility,
                                  Governance.BlanketPO bpo, String source) {
        return Governance.evaluate(new Governance.Input(b, lines, total, facility, "", bpo, source, TODAY));
    }

    static Governance.Result eval(double total, String source) {
        return eval(buyer(), List.of(), total, "", null, source);
    }

    @Test
    void autoApprovedWithinLimits() {
        assertEquals(Governance.AUTO_APPROVED, eval(900, "web").outcome());
    }

    @Test
    void monthlyLimitRoutesToDirectApprover() {
        var r = eval(1400, "web");
        assertEquals(Governance.APPROVAL_REQUIRED, r.outcome());
        assertEquals("u-marcus", r.approver().id());
    }

    @Test
    void largeOrderEscalatesPastDirectApprover() {
        var r = eval(25000, "web");
        assertEquals(Governance.APPROVAL_REQUIRED, r.outcome());
        assertEquals("u-marcus", r.approver().id(), "routing goes to the direct approver first");
        var last = r.checks().get(r.checks().size() - 1);
        assertTrue(last.message().contains("escalates to Ruth"), last.message());
        assertEquals("u-ruth", Governance.firstApprover(CHAIN, 25000).orElseThrow().id(), "firstApprover should skip Marcus");
    }

    @Test
    void noApproverWithAuthorityBlocks() {
        assertEquals(Governance.BLOCKED, eval(250000, "web").outcome());
    }

    @Test
    void restrictedCategoryNeedsCertification() {
        var lines = List.of(new Governance.Line("2YDW3", "", false,
                new Governance.Restriction("Safety Switches", "LICENSED_ELECTRICIAN")));
        assertEquals(Governance.BLOCKED, eval(buyer(), lines, 100, "", null, "web").outcome(), "uncertified buyer should be blocked");
        assertEquals(Governance.AUTO_APPROVED, eval(buyer(List.of("LICENSED_ELECTRICIAN")), lines, 100, "", null, "web").outcome(),
                "certified buyer should pass");
    }

    @Test
    void blanketPOChecks() {
        var bpo = new Governance.BlanketPO("BPO-1", "fac-mke", 25000, 24500, TODAY.minusMonths(9), TODAY.plusMonths(2));
        assertEquals(Governance.BLOCKED, eval(buyer(), List.of(), 600, "fac-mke", bpo, "web").outcome(),
                "over-consumed blanket PO should block");
        assertEquals(Governance.BLOCKED, eval(buyer(), List.of(), 100, "fac-ict", bpo, "web").outcome(), "other facility should block");
        var expired = new Governance.BlanketPO("BPO-1", "fac-mke", 25000, 24500, TODAY.minusMonths(9), TODAY.minusMonths(1));
        assertEquals(Governance.BLOCKED, eval(buyer(), List.of(), 100, "fac-mke", expired, "web").outcome(),
                "expired blanket PO should block");
        assertEquals(Governance.AUTO_APPROVED, eval(buyer(), List.of(), 100, "fac-mke", bpo, "web").outcome());
    }

    @Test
    void ediSkipsSpendLimitsButNotRestrictions() {
        assertEquals(Governance.AUTO_APPROVED, eval(50000, "edi").outcome(), "ERP-approved EDI order should not need approval");
        var lines = List.of(new Governance.Line("9KL33", "", false, new Governance.Restriction("Chemicals", "HAZCOM_TRAINED")));
        assertEquals(Governance.BLOCKED, eval(buyer(), lines, 50, "", null, "edi").outcome(), "restrictions still apply to EDI");
    }

    @Test
    void keepStockReplenishmentIsPreAuthorized() {
        var lines = List.of(new Governance.Line("1BKR6", "", false, new Governance.Restriction("Breakers", "LICENSED_ELECTRICIAN")));
        assertEquals(Governance.AUTO_APPROVED, eval(buyer(), lines, 99999, "", null, "keepstock").outcome());
    }
}
