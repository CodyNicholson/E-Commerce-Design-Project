package com.example.mromirror.pricing;

import com.example.mromirror.support.Go;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the net price one customer pays for one SKU.
 *
 * <p>B2B distributors rarely have "a price". The list price is only the starting
 * point; the buyer's corporation may hold a negotiated contract price for the
 * SKU, a percentage discount on the whole category, and everyone gets volume
 * breaks. {@link #resolve} evaluates every candidate and the lowest one wins
 * ("best price"), returning the full trace so the UI can explain why.
 */
public final class Pricing {
    private Pricing() {}

    /** A customer-specific fixed net price for a SKU. */
    public record Contract(double price, String ref, LocalDate validTo) {}

    /** A customer-specific % off list for a category subtree. */
    public record CategoryDiscount(String categoryId, String categoryName, double pct) {}

    /** A volume break: buying at least minQty earns pct off list. */
    public record Tier(String categoryId, int minQty, double pct) {}

    /**
     * @param categoryDiscount the most specific discount found walking up the category tree
     * @param tiers            the volume breaks of the most specific category that has any
     */
    public record Input(double listPrice, int qty, Contract contract, CategoryDiscount categoryDiscount,
                        List<Tier> tiers, LocalDate today) {}

    /** source: list | contract | category | volume */
    public record Candidate(String source, String label, double unitPrice, boolean applies,
                            @JsonInclude(JsonInclude.Include.NON_EMPTY) String reason) {}

    public record Result(double listPrice, double unitPrice, int qty, double extended, String source, String label,
                         double savingsPct, List<Candidate> candidates,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Tier nextTier) {}

    /** Rounds a currency amount to cents, half away from zero. */
    public static double round2(double v) {
        return Go.round(v * 100) / 100;
    }

    public static Result resolve(Input in) {
        int qty = Math.max(in.qty(), 1);
        double list = round2(in.listPrice());
        List<Candidate> cands = new ArrayList<>();
        cands.add(new Candidate("list", "List price", list, true, ""));

        Contract c = in.contract();
        if (c != null) {
            boolean applies = true;
            String reason = "";
            if (c.validTo() != null && in.today() != null && in.today().isAfter(c.validTo())) {
                applies = false;
                reason = "expired " + c.validTo();
            }
            cands.add(new Candidate("contract", "Contract " + c.ref(), round2(c.price()), applies, reason));
        }

        CategoryDiscount d = in.categoryDiscount();
        if (d != null && d.pct() > 0) {
            cands.add(new Candidate("category",
                    "Category discount " + fmtPct(d.pct()) + "% (" + d.categoryName() + ")",
                    round2(list * (1 - d.pct() / 100)), true, ""));
        }

        Tier best = null;
        Tier next = null;
        if (in.tiers() != null) {
            for (Tier t : in.tiers()) {
                if (qty >= t.minQty()) {
                    if (best == null || t.pct() > best.pct()) {
                        best = t;
                    }
                } else if (next == null || t.minQty() < next.minQty()) {
                    next = t;
                }
            }
        }
        if (best != null) {
            cands.add(new Candidate("volume",
                    "Volume tier " + fmtPct(best.pct()) + "% (" + best.minQty() + "+)",
                    round2(list * (1 - best.pct() / 100)), true, ""));
        }

        int win = 0;
        for (int i = 0; i < cands.size(); i++) {
            if (cands.get(i).applies() && cands.get(i).unitPrice() < cands.get(win).unitPrice()) {
                win = i;
            }
        }
        double unit = cands.get(win).unitPrice();
        double savings = 0;
        if (list > 0) {
            savings = Go.round((1 - unit / list) * 1000) / 10;
        }
        return new Result(list, unit, qty, round2(unit * qty), cands.get(win).source(), cands.get(win).label(),
                savings, cands, next);
    }

    static String fmtPct(double p) {
        return p == Math.floor(p) ? Go.fixed(p, 0) : Go.fixed(p, 1);
    }
}
