package com.example.mromirror.governance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.example.mromirror.support.Go.fixed;

/**
 * Decides whether a corporate buyer may place an order.
 *
 * <p>In B2B the customer is a hierarchy, not a person. The buyer's own spend
 * limits, the certifications the corporation demands for restricted
 * categories, the blanket PO being charged and the approval chain all have a
 * say. {@link #evaluate} returns every check it ran, plus the outcome:
 * auto_approved, approval_required (with the approver) or blocked.
 */
public final class Governance {
    private Governance() {}

    public static final String AUTO_APPROVED = "auto_approved";
    public static final String APPROVAL_REQUIRED = "approval_required";
    public static final String BLOCKED = "blocked";

    public record Approver(String id, String name, String title, double perOrderLimit) {}

    /** @param chain the approval chain, direct approver first */
    public record Buyer(String id, double perOrderLimit, double monthlyLimit, double mtdSpend, List<String> certs,
                        List<Approver> chain) {}

    public record Restriction(String categoryName, String cert) {}

    public record Line(String sku, String name, boolean discontinued, Restriction restriction) {}

    public record BlanketPO(String number, String facilityId, double authorized, double consumed,
                            LocalDate validFrom, LocalDate validTo) {}

    /**
     * @param source web | punchout | edi | keepstock. EDI orders were already approved in the
     *               buyer's ERP and KeepStock replenishment is pre-authorized, so spend limits are skipped.
     */
    public record Input(Buyer buyer, List<Line> lines, double total, String facilityId, String poNumber,
                        BlanketPO blanketPO, String source, LocalDate today) {}

    /** level: pass | info | approval | block */
    public record Check(String code, String level, String message) {}

    public record Result(String outcome, List<Check> checks,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Approver approver) {}

    public static Result evaluate(Input in) {
        List<Check> checks = new ArrayList<>();
        Buyer buyer = in.buyer();
        String source = in.source() == null ? "" : in.source();

        if (in.lines() != null && !source.equals("keepstock")) {
            // KeepStock skips this: the VMI agreement pre-approved every SKU stocked in the program
            for (Line l : in.lines()) {
                if (l.discontinued()) {
                    checks.add(new Check("discontinued", "block", l.sku() + " is discontinued; order its replacement instead."));
                }
                Restriction r = l.restriction();
                if (r != null) {
                    if (buyer.certs() != null && buyer.certs().contains(r.cert())) {
                        checks.add(new Check("restricted_category", "pass",
                                l.sku() + " (" + r.categoryName() + ") requires " + r.cert() + "; buyer holds it."));
                    } else {
                        checks.add(new Check("restricted_category", "block",
                                l.sku() + " (" + r.categoryName() + ") requires " + r.cert()
                                        + " certification, which this buyer does not hold."));
                    }
                }
            }
        }

        BlanketPO b = in.blanketPO();
        if (b != null) {
            double remaining = b.authorized() - b.consumed();
            if (in.today().isBefore(b.validFrom()) || in.today().isAfter(b.validTo())) {
                checks.add(new Check("blanket_po", "block",
                        "Blanket PO " + b.number() + " is only valid " + b.validFrom() + " to " + b.validTo() + "."));
            } else if (b.facilityId() != null && !b.facilityId().isEmpty() && !b.facilityId().equals(in.facilityId())) {
                checks.add(new Check("blanket_po", "block", "Blanket PO " + b.number() + " belongs to a different facility."));
            } else if (in.total() > remaining) {
                checks.add(new Check("blanket_po", "block", "Blanket PO " + b.number() + " has $" + fixed(remaining, 2)
                        + " remaining; order total is $" + fixed(in.total(), 2) + "."));
            } else {
                checks.add(new Check("blanket_po", "pass", "Charged to blanket PO " + b.number() + " ($"
                        + fixed(remaining - in.total(), 2) + " remaining after this order)."));
            }
        } else if ((in.poNumber() == null || in.poNumber().isEmpty()) && source.equals("web")) {
            checks.add(new Check("po_number", "info", "No PO number entered; the order will be invoiced to the cost center."));
        }

        boolean needsApproval = false;
        switch (source) {
            case "edi" -> checks.add(new Check("erp_approved", "info",
                    "Approved in the buyer's ERP before transmission; spend limits are enforced there."));
            case "keepstock" -> checks.add(new Check("keepstock_program", "info",
                    "KeepStock replenishment is pre-authorized by the VMI program agreement (items, quantities and spend)."));
            default -> {
                if (in.total() > buyer.perOrderLimit()) {
                    needsApproval = true;
                    checks.add(new Check("per_order_limit", "approval", "Order total $" + fixed(in.total(), 2)
                            + " exceeds the buyer's per-order limit of $" + fixed(buyer.perOrderLimit(), 2) + "."));
                } else {
                    checks.add(new Check("per_order_limit", "pass",
                            "Within per-order limit of $" + fixed(buyer.perOrderLimit(), 2) + "."));
                }
                double projected = buyer.mtdSpend() + in.total();
                if (projected > buyer.monthlyLimit()) {
                    needsApproval = true;
                    checks.add(new Check("monthly_limit", "approval", "Month-to-date spend would reach $" + fixed(projected, 2)
                            + ", over the monthly limit of $" + fixed(buyer.monthlyLimit(), 2) + "."));
                } else {
                    checks.add(new Check("monthly_limit", "pass", "Month-to-date spend after this order: $"
                            + fixed(projected, 2) + " of $" + fixed(buyer.monthlyLimit(), 2) + "."));
                }
            }
        }

        for (Check c : checks) {
            if (c.level().equals("block")) {
                return new Result(BLOCKED, checks, null);
            }
        }
        if (needsApproval) {
            List<Approver> chain = buyer.chain() == null ? List.of() : buyer.chain();
            Optional<Approver> fin = firstApprover(chain, in.total());
            if (fin.isEmpty()) {
                checks.add(new Check("approval_chain", "block",
                        "No approver in the chain has authority for $" + fixed(in.total(), 2) + "."));
                return new Result(BLOCKED, checks, null);
            }
            // The requisition always starts with the direct approver, who escalates
            // it if the total is beyond their own authority.
            Approver direct = chain.get(0);
            Approver last = fin.get();
            String msg = "Routed to " + direct.name() + " (" + direct.title() + ", authority $"
                    + fixed(direct.perOrderLimit(), 0) + ").";
            if (!last.id().equals(direct.id())) {
                msg += " Total exceeds that authority, so it escalates to " + last.name() + " (" + last.title()
                        + ", authority $" + fixed(last.perOrderLimit(), 0) + ").";
            }
            checks.add(new Check("approval_chain", "approval", msg));
            return new Result(APPROVAL_REQUIRED, checks, direct);
        }
        return new Result(AUTO_APPROVED, checks, null);
    }

    /**
     * Walks up the chain and returns the first approver whose per-order
     * authority covers total; approvers with too little authority are skipped.
     */
    public static Optional<Approver> firstApprover(List<Approver> chain, double total) {
        for (Approver a : chain) {
            if (a.perOrderLimit() >= total) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }
}
