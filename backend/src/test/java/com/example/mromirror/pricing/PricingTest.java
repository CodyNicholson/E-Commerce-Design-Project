package com.example.mromirror.pricing;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PricingTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @Test
    void listPriceWhenNoAgreements() {
        var r = Pricing.resolve(new Pricing.Input(612, 2, null, null, null, TODAY));
        assertEquals(612, r.unitPrice());
        assertEquals("list", r.source());
        assertEquals(1224, r.extended());
    }

    @Test
    void contractBeatsCategoryDiscount() {
        var r = Pricing.resolve(new Pricing.Input(612, 1, new Pricing.Contract(539, "MSA-1", TODAY.plusYears(1)),
                new Pricing.CategoryDiscount("motors", "Motors", 8), null, TODAY));
        assertEquals("contract", r.source());
        assertEquals(539, r.unitPrice());
        assertEquals(3, r.candidates().size());
    }

    @Test
    void expiredContractIsIgnored() {
        var r = Pricing.resolve(new Pricing.Input(486, 1, new Pricing.Contract(470, "OLD", TODAY.minusMonths(1)),
                new Pricing.CategoryDiscount(null, "Motors", 2), null, TODAY));
        assertEquals("category", r.source());
        assertEquals(476.28, r.unitPrice());
        assertFalse(r.candidates().get(1).applies());
        assertFalse(r.candidates().get(1).reason().isEmpty(), "expired contract should be flagged");
    }

    @Test
    void volumeTierWinsAtHighQuantity() {
        var tiers = List.of(new Pricing.Tier(null, 10, 10), new Pricing.Tier(null, 25, 20), new Pricing.Tier(null, 100, 30));
        var disc = new Pricing.CategoryDiscount(null, "Fasteners", 25);

        var low = Pricing.resolve(new Pricing.Input(10, 30, null, disc, tiers, TODAY));
        assertEquals("category", low.source(), "at qty 30 the 25% category discount should win");
        assertEquals(7.5, low.unitPrice());
        assertNotNull(low.nextTier());
        assertEquals(100, low.nextTier().minQty());

        var high = Pricing.resolve(new Pricing.Input(10, 100, null, disc, tiers, TODAY));
        assertEquals("volume", high.source(), "at qty 100 the 30% tier should win");
        assertEquals(7, high.unitPrice());
        assertEquals(700, high.extended());
        assertEquals(30, high.savingsPct());
    }

    @Test
    void roundingToCents() {
        var r = Pricing.resolve(new Pricing.Input(22.10, 3, null, new Pricing.CategoryDiscount(null, null, 10), null, TODAY));
        assertEquals(19.89, r.unitPrice());
        assertEquals(59.67, r.extended());
    }

    @Test
    void labelsFormatPercentages() {
        var r = Pricing.resolve(new Pricing.Input(100, 1, null, new Pricing.CategoryDiscount(null, "Motors", 12.5), null, TODAY));
        assertEquals("Category discount 12.5% (Motors)", r.label());
        r = Pricing.resolve(new Pricing.Input(100, 1, null, new Pricing.CategoryDiscount(null, "Motors", 8), null, TODAY));
        assertEquals("Category discount 8% (Motors)", r.label());
    }
}
