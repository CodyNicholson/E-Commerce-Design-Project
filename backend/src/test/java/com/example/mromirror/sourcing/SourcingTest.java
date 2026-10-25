package com.example.mromirror.sourcing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourcingTest {
    // A miniature network around a Milwaukee plant.
    static final List<Sourcing.Node> NODES = List.of(
            new Sourcing.Node("dc-min", "dc", "Minooka DC", 41.4553, -88.2620, true, false, 0),
            new Sourcing.Node("dc-kc", "dc", "Kansas City DC", 39.0997, -94.5786, true, false, 0),
            new Sourcing.Node("br-mke", "branch", "Milwaukee Branch", 43.0305, -87.95, false, true, 0),
            new Sourcing.Node("sup", "supplier", "Supplier", 35.38, -94.39, false, false, 5));

    static final double PLANT_LAT = 43.0389;
    static final double PLANT_LON = -87.9065;

    static Sourcing.Plan build(List<Sourcing.Line> lines, Map<String, Map<String, Integer>> atp, String method, String pref) {
        return Sourcing.build(new Sourcing.Request(lines, NODES, atp, PLANT_LAT, PLANT_LON, method, pref));
    }

    static Sourcing.Line line(String sku, int qty) {
        return new Sourcing.Line(sku, qty, false, false);
    }

    @Test
    void willCallTakesBranchStockThenShipsRemainder() {
        var p = build(List.of(line("MOTOR", 3)), Map.of("br-mke", Map.of("MOTOR", 2), "dc-min", Map.of("MOTOR", 10)),
                "will_call", "ship_available");
        assertEquals(2, p.shipments.size());
        assertTrue(p.split);
        assertEquals("will_call", p.shipments.get(0).method);
        assertEquals(2, p.shipments.get(0).lines.get(0).qty());
        assertEquals("dc-min", p.shipments.get(1).nodeId, "remainder should ship from Minooka");
        assertEquals(1, p.shipments.get(1).lines.get(0).qty());
    }

    @Test
    void shipUsesCourierFromNearbyBranch() {
        var p = build(List.of(line("BRG", 5)), Map.of("br-mke", Map.of("BRG", 30), "dc-min", Map.of("BRG", 300)),
                "ship", "ship_available");
        assertEquals(1, p.shipments.size());
        assertEquals("courier", p.shipments.get(0).method);
        assertEquals(0, p.shipments.get(0).etaDays);
    }

    @Test
    void hazmatSkipsNonLicensedBranchAndCourier() {
        var p = build(List.of(new Sourcing.Line("AEROSOL", 4, true, false)),
                Map.of("br-mke", Map.of("AEROSOL", 50), "dc-min", Map.of("AEROSOL", 50)), "will_call", "ship_available");
        assertEquals(1, p.shipments.size());
        assertEquals("dc-min", p.shipments.get(0).nodeId);
        assertEquals("hazmat_ground", p.shipments.get(0).method);
    }

    @Test
    void splitAcrossDCsAndBackorder() {
        var p = build(List.of(line("WASHDOWN", 8)), Map.of("dc-min", Map.of("WASHDOWN", 1), "dc-kc", Map.of("WASHDOWN", 4)),
                "ship", "ship_available");
        assertEquals(2, p.shipments.size());
        assertEquals(1, p.backorders.size());
        assertEquals(3, p.backorders.get(0).qty());
        assertEquals("dc-min", p.shipments.get(0).nodeId, "closest DC first");
    }

    @Test
    void shipCompletePrefersSingleNode() {
        var lines = List.of(line("A", 2), line("B", 2));
        Map<String, Map<String, Integer>> atp = Map.of(
                "br-mke", Map.of("A", 5, "B", 0),
                "dc-min", Map.of("A", 0, "B", 5),
                "dc-kc", Map.of("A", 5, "B", 5));
        assertEquals(2, build(lines, atp, "ship", "ship_available").shipments.size(), "ship-available should split");
        var complete = build(lines, atp, "ship", "ship_complete");
        assertEquals(1, complete.shipments.size());
        assertEquals("dc-kc", complete.shipments.get(0).nodeId, "ship-complete should consolidate at KC");
    }

    @Test
    void shipCompleteHoldsWhenNoSingleNode() {
        var p = build(List.of(line("A", 2), line("B", 2)), Map.of("br-mke", Map.of("A", 5), "dc-kc", Map.of("B", 5)),
                "ship", "ship_complete");
        assertTrue(p.heldForConsolidation);
        assertEquals(2, p.shipments.size());
        assertEquals(p.shipments.get(0).etaDays, p.shipments.get(1).etaDays, "held shipments should share the latest ETA");
        assertFalse(p.shipments.get(0).note.isEmpty());
    }

    @Test
    void dropShipOnlyGoesToSupplier() {
        var p = build(List.of(new Sourcing.Line("BIG", 1, false, true)), Map.of("dc-min", Map.of("BIG", 10), "sup", Map.of("BIG", 3)),
                "ship", "ship_complete");
        assertEquals(1, p.shipments.size());
        assertEquals("drop_ship", p.shipments.get(0).method);
    }

    @Test
    void distance() {
        double d = Sourcing.distanceMi(PLANT_LAT, PLANT_LON, 41.4553, -88.2620);
        assertTrue(d >= 105 && d <= 115, "Milwaukee to Minooka should be ~110 mi, got " + d);
    }
}
