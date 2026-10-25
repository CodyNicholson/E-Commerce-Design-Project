package com.example.mromirror.sourcing;

import com.example.mromirror.support.Go;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The OMS brain: it turns a cart into shipments.
 *
 * <p>Inventory sits in three kinds of node: regional distribution centers (DCs),
 * local branches (will-call pickup and same-day courier) and supplier
 * drop-ship locations. {@link #build} computes available-to-promise per node and
 * allocates each line while honouring:
 * <ul>
 *   <li>hazmat lines only from nodes licensed to store them, never by courier</li>
 *   <li>drop-ship-only items only from suppliers</li>
 *   <li>will-call pickup at the nearest branch first</li>
 *   <li>ship-complete (one node or hold for consolidation) vs ship-available
 *       (split freely, emergency partials, backorder the rest)</li>
 * </ul>
 */
public final class Sourcing {
    private Sourcing() {}

    public static final int COURIER_RADIUS_MI = 60;
    // Ground transit is modelled as one business day per this many miles, plus one.
    private static final int GROUND_MILES_PER_DAY = 450;
    private static final int BACKORDER_DAYS = 10;

    /** kind: dc | branch | supplier */
    public record Node(String id, String kind, String name, double lat, double lon, boolean hazmatCapable,
                       boolean willCall, int leadDays) {}

    public record Line(String sku, int qty, boolean hazmat, boolean dropShipOnly) {}

    /**
     * Available-to-promise: node ID -> SKU -> units (on hand minus reserved).
     *
     * @param lat            ship-to facility
     * @param deliveryMethod ship | will_call
     * @param pref           ship_available | ship_complete
     */
    public record Request(List<Line> lines, List<Node> nodes, Map<String, Map<String, Integer>> atp,
                          double lat, double lon, String deliveryMethod, String pref) {}

    public record Allocation(String sku, int qty) {}

    public static final class Shipment {
        public String nodeId;
        public String nodeName;
        public String nodeKind;
        /** will_call | courier | ground | hazmat_ground | drop_ship */
        public String method;
        public int etaDays;
        public String etaLabel;
        public double distanceMi;
        public List<Allocation> lines = new ArrayList<>();
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public String note = "";
    }

    public static final class Plan {
        public List<Shipment> shipments = new ArrayList<>();
        public List<Allocation> backorders = new ArrayList<>();
        public int backorderEtaDays;
        public boolean split;
        public boolean heldForConsolidation;
        public List<String> notes = new ArrayList<>();
    }

    private record Candidate(Node node, double dist, String method, int eta) {}

    /** Merges allocations from the same node and method into one shipment. */
    private record GroupKey(String node, String method) {}

    /** The great-circle distance between two points. */
    public static double distanceMi(double lat1, double lon1, double lat2, double lon2) {
        final double r = 3958.8;
        double rad = Math.PI / 180;
        double dLat = (lat2 - lat1) * rad;
        double dLon = (lon2 - lon1) * rad;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static int groundDays(double dist) {
        return 1 + (int) (dist / GROUND_MILES_PER_DAY);
    }

    /** Lists, in preference order, the nodes (with method and ETA) that may fulfil a line. */
    private static List<Candidate> candidatesFor(Line l, Request req, Map<String, Double> dists) {
        List<Candidate> out = new ArrayList<>();
        if (l.dropShipOnly()) {
            for (Node n : req.nodes()) {
                if (n.kind().equals("supplier") && (!l.hazmat() || n.hazmatCapable())) {
                    out.add(new Candidate(n, dists.get(n.id()), "drop_ship", n.leadDays() + groundDays(dists.get(n.id()))));
                }
            }
            sortByEta(out);
            return out;
        }

        if ("will_call".equals(req.deliveryMethod())) {
            Node b = nearestWillCall(req.nodes(), dists);
            if (b != null && (!l.hazmat() || b.hazmatCapable())) {
                out.add(new Candidate(b, dists.get(b.id()), "will_call", 0));
            }
        }

        List<Candidate> ship = new ArrayList<>();
        for (Node n : req.nodes()) {
            if (l.hazmat() && !n.hazmatCapable()) {
                continue;
            }
            double d = dists.get(n.id());
            switch (n.kind()) {
                case "branch" -> {
                    if (d > COURIER_RADIUS_MI) {
                        continue; // branches only ship locally; their stock serves walk-in customers
                    }
                    ship.add(l.hazmat() ? new Candidate(n, d, "hazmat_ground", 1) : new Candidate(n, d, "courier", 0));
                }
                case "dc" -> ship.add(new Candidate(n, d, l.hazmat() ? "hazmat_ground" : "ground", groundDays(d)));
                default -> {
                }
            }
        }
        sortByEta(ship);
        out.addAll(ship);

        // Suppliers are the last resort before backorder.
        List<Candidate> sup = new ArrayList<>();
        for (Node n : req.nodes()) {
            if (n.kind().equals("supplier") && (!l.hazmat() || n.hazmatCapable())) {
                sup.add(new Candidate(n, dists.get(n.id()), "drop_ship", n.leadDays() + groundDays(dists.get(n.id()))));
            }
        }
        sortByEta(sup);
        out.addAll(sup);
        return out;
    }

    private static void sortByEta(List<Candidate> c) {
        c.sort(Comparator.comparingInt(Candidate::eta).thenComparingDouble(Candidate::dist));
    }

    private static Node nearestWillCall(List<Node> nodes, Map<String, Double> dists) {
        Node best = null;
        for (Node n : nodes) {
            if (n.kind().equals("branch") && n.willCall() && (best == null || dists.get(n.id()) < dists.get(best.id()))) {
                best = n;
            }
        }
        return best;
    }

    private static int atpOf(Map<String, Map<String, Integer>> atp, String node, String sku) {
        Map<String, Integer> skus = atp.get(node);
        return skus == null ? 0 : skus.getOrDefault(sku, 0);
    }

    public static Plan build(Request req) {
        Map<String, Double> dists = new HashMap<>();
        for (Node n : req.nodes()) {
            dists.put(n.id(), distanceMi(req.lat(), req.lon(), n.lat(), n.lon()));
        }
        Map<String, Map<String, Integer>> atp = new HashMap<>();
        req.atp().forEach((node, skus) -> atp.put(node, new HashMap<>(skus)));

        List<List<Candidate>> cands = new ArrayList<>();
        for (Line l : req.lines()) {
            cands.add(candidatesFor(l, req, dists));
        }

        Plan plan = new Plan();
        Map<GroupKey, Shipment> groups = new LinkedHashMap<>();
        Allocator add = (c, sku, qty) -> {
            Shipment s = groups.computeIfAbsent(new GroupKey(c.node().id(), c.method()), k -> {
                Shipment n = new Shipment();
                n.nodeId = c.node().id();
                n.nodeName = c.node().name();
                n.nodeKind = c.node().kind();
                n.method = c.method();
                n.etaDays = c.eta();
                n.etaLabel = etaLabel(c.method(), c.eta());
                n.distanceMi = Go.round(c.dist());
                return n;
            });
            s.lines.add(new Allocation(sku, qty));
            atp.computeIfAbsent(c.node().id(), k -> new HashMap<>()).merge(sku, -qty, Integer::sum);
        };

        if ("ship_complete".equals(req.pref()) && hasStockedLine(req.lines())) {
            String node = singleNode(req, cands, atp);
            if (node != null) {
                for (int i = 0; i < req.lines().size(); i++) {
                    Line l = req.lines().get(i);
                    if (l.dropShipOnly()) {
                        continue;
                    }
                    for (Candidate c : cands.get(i)) {
                        if (c.node().id().equals(node)) {
                            add.add(c, l.sku(), l.qty());
                            break;
                        }
                    }
                }
                plan.notes.add("Ship complete: every stocked line fits in one node.");
                // Drop-ship-only lines can never consolidate; allocate them on their own.
                for (int i = 0; i < req.lines().size(); i++) {
                    Line l = req.lines().get(i);
                    if (l.dropShipOnly()) {
                        allocateGreedy(l, cands.get(i), atp, add, plan);
                    }
                }
                return finish(plan, groups);
            }
            plan.heldForConsolidation = true;
            plan.notes.add("Ship complete requested but no single node holds every line; lines are allocated across nodes and held so the order arrives together.");
        }

        for (int i = 0; i < req.lines().size(); i++) {
            allocateGreedy(req.lines().get(i), cands.get(i), atp, add, plan);
        }
        return finish(plan, groups);
    }

    @FunctionalInterface
    private interface Allocator {
        void add(Candidate c, String sku, int qty);
    }

    private static void allocateGreedy(Line l, List<Candidate> cands, Map<String, Map<String, Integer>> atp,
                                       Allocator add, Plan plan) {
        int need = l.qty();
        for (Candidate c : cands) {
            if (need == 0) {
                break;
            }
            int avail = atpOf(atp, c.node().id(), l.sku());
            if (avail <= 0) {
                continue;
            }
            int take = Math.min(avail, need);
            add.add(c, l.sku(), take);
            need -= take;
        }
        if (need > 0) {
            plan.backorders.add(new Allocation(l.sku(), need));
        }
    }

    private static boolean hasStockedLine(List<Line> lines) {
        return lines.stream().anyMatch(l -> !l.dropShipOnly());
    }

    /** Returns the first preferred node that can fill every stocked line alone, or null. */
    private static String singleNode(Request req, List<List<Candidate>> cands, Map<String, Map<String, Integer>> atp) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < req.lines().size(); i++) {
            if (req.lines().get(i).dropShipOnly()) {
                continue;
            }
            for (Candidate c : cands.get(i)) {
                if (seen.contains(c.node().id()) || c.node().kind().equals("supplier")) {
                    continue;
                }
                seen.add(c.node().id());
                if (nodeCovers(c.node().id(), req, cands, atp)) {
                    return c.node().id();
                }
            }
        }
        return null;
    }

    private static boolean nodeCovers(String node, Request req, List<List<Candidate>> cands,
                                      Map<String, Map<String, Integer>> atp) {
        for (int i = 0; i < req.lines().size(); i++) {
            Line l = req.lines().get(i);
            if (l.dropShipOnly()) {
                continue;
            }
            boolean allowed = cands.get(i).stream().anyMatch(c -> c.node().id().equals(node));
            if (!allowed || atpOf(atp, node, l.sku()) < l.qty()) {
                return false;
            }
        }
        return true;
    }

    private static Plan finish(Plan plan, Map<GroupKey, Shipment> groups) {
        plan.shipments.addAll(groups.values());
        plan.shipments.sort(Comparator.comparingInt(s -> s.etaDays));

        if (!plan.backorders.isEmpty()) {
            plan.backorderEtaDays = BACKORDER_DAYS;
            plan.notes.add(plan.backorders.size() + " line(s) backordered; expected in ~" + BACKORDER_DAYS + " business days.");
        }
        if (plan.heldForConsolidation && plan.shipments.size() > 1) {
            int latest = 0;
            for (Shipment s : plan.shipments) {
                latest = Math.max(latest, s.etaDays);
            }
            for (Shipment s : plan.shipments) {
                if (!s.method.equals("will_call") && s.etaDays < latest) {
                    s.note = "held " + (latest - s.etaDays) + " day(s) to arrive with the rest of the order";
                    s.etaDays = latest;
                    s.etaLabel = etaLabel(s.method, latest);
                }
            }
        }
        for (Shipment s : plan.shipments) {
            if (s.method.equals("hazmat_ground")) {
                plan.notes.add("Hazmat lines ship ground only (DOT 49 CFR) from hazmat-licensed nodes.");
                break;
            }
        }
        plan.split = plan.shipments.size() > 1 || (!plan.shipments.isEmpty() && !plan.backorders.isEmpty());
        return plan;
    }

    private static String etaLabel(String method, int days) {
        return switch (method) {
            case "will_call" -> "Ready for pickup in ~1 hour";
            case "courier" -> "Same-day courier";
            case "hazmat_ground" -> "Ground only (hazmat), " + plural(days, "business day");
            case "drop_ship" -> "Drop-ship from supplier, ~" + plural(days, "business day");
            default -> "Ground, " + plural(days, "business day");
        };
    }

    private static String plural(int n, String unit) {
        return n == 1 ? "1 " + unit : n + " " + unit + "s";
    }
}
