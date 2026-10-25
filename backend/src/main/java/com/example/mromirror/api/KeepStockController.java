package com.example.mromirror.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.mromirror.api.Json.obj;

/**
 * KeepStock is vendor-managed inventory inside the customer's plant:
 * barcode-labelled bins that are cycle-counted, and badge-swipe vending
 * machines. Either one dropping to its reorder point raises a replenishment
 * order automatically, charged to the owning cost center.
 */
@RestController
@RequestMapping("/api/keepstock")
public class KeepStockController {

    public static final class Bin {
        public String id;
        public String facilityId;
        public String costCenterId;
        public String costCenter;
        public String sku;
        public String name;
        public String location;
        public int minQty;
        public int maxQty;
        public int onHand;
        public int onOrder;
        public Instant lastScannedAt;
    }

    private static final String BIN_SQL = """
            SELECT b.id, b.facility_id, b.cost_center_id, cc.code || ' ' || cc.name, b.sku, p.name, b.location,
                b.min_qty, b.max_qty, b.on_hand, b.on_order, b.last_scanned_at
            FROM keepstock.bins b JOIN pim.products p ON p.sku = b.sku JOIN accounts.org_units cc ON cc.id = b.cost_center_id""";

    private static Bin mapBin(ResultSet rs, int row) throws SQLException {
        Bin b = new Bin();
        b.id = rs.getString(1);
        b.facilityId = rs.getString(2);
        b.costCenterId = rs.getString(3);
        b.costCenter = rs.getString(4);
        b.sku = rs.getString(5);
        b.name = rs.getString(6);
        b.location = rs.getString(7);
        b.minQty = rs.getInt(8);
        b.maxQty = rs.getInt(9);
        b.onHand = rs.getInt(10);
        b.onOrder = rs.getInt(11);
        b.lastScannedAt = Sql.instant(rs, 12);
        return b;
    }

    public static final class Slot {
        public String machineId;
        public String slot;
        public String sku;
        public String name;
        public int onHand;
        public int capacity;
        public int par;
        public int onOrder;
        public int dailyLimit;
    }

    private static final String SLOT_SQL = """
            SELECT v.machine_id, v.slot, v.sku, p.name, v.on_hand, v.capacity, v.par, v.on_order, v.daily_limit
            FROM keepstock.vending_slots v JOIN pim.products p ON p.sku = v.sku""";

    private static Slot mapSlot(ResultSet rs, int row) throws SQLException {
        Slot s = new Slot();
        s.machineId = rs.getString(1);
        s.slot = rs.getString(2);
        s.sku = rs.getString(3);
        s.name = rs.getString(4);
        s.onHand = rs.getInt(5);
        s.capacity = rs.getInt(6);
        s.par = rs.getInt(7);
        s.onOrder = rs.getInt(8);
        s.dailyLimit = rs.getInt(9);
        return s;
    }

    private final Sql sql;
    private final Accounts accounts;
    private final Inventory inventory;
    private final Checkout checkout;
    private final Bodies bodies;
    private final TransactionTemplate tx;

    public KeepStockController(Sql sql, Accounts accounts, Inventory inventory, Checkout checkout, Bodies bodies,
                               TransactionTemplate tx) {
        this.sql = sql;
        this.accounts = accounts;
        this.inventory = inventory;
        this.checkout = checkout;
        this.bodies = bodies;
        this.tx = tx;
    }

    /** Guards KeepStock actions to facilities of the acting user's corporation. */
    private void facilityInCorp(String facilityId, Accounts.User u) {
        Inventory.Facility f;
        try {
            f = inventory.loadFacility(facilityId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.notFound("no facility " + facilityId);
        }
        if (!f.corporationId().equals(u.corporationId())) {
            throw ApiException.forbidden("facility belongs to another corporation");
        }
    }

    record Fac(String id, String name) {}

    record Machine(String id, String name, String location, String costCenter, List<Slot> slots) {}

    record Event(int id, String machineId, String slot, String badgeId, String userName, String sku, int qty, String outcome,
                 String message, Instant createdAt) {}

    record Badge(String badgeId, String name) {}

    @GetMapping
    public Map<String, Object> overview(Accounts.User u, HttpServletRequest r) {
        String facId = Optional.ofNullable(r.getParameter("facilityId")).orElse("");
        if (facId.isEmpty()) {
            facId = u.facilityId();
        }
        facilityInCorp(facId, u);
        List<Fac> facilities = sql.list("""
                SELECT f.id, f.name FROM accounts.org_units f
                WHERE f.kind = 'facility' AND f.parent_id IN (SELECT id FROM accounts.org_units WHERE parent_id = ?)
                  AND (EXISTS (SELECT 1 FROM keepstock.bins b WHERE b.facility_id = f.id)
                    OR EXISTS (SELECT 1 FROM keepstock.vending_machines m WHERE m.facility_id = f.id))
                ORDER BY f.name""", (rs, i) -> new Fac(rs.getString(1), rs.getString(2)), u.corporationId());
        List<Bin> bins = sql.list(BIN_SQL + " WHERE b.facility_id = ? ORDER BY b.id", KeepStockController::mapBin, facId);
        List<Machine> machines = new ArrayList<>();
        for (Machine m : sql.list("""
                SELECT m.id, m.name, m.location, cc.code || ' ' || cc.name FROM keepstock.vending_machines m
                JOIN accounts.org_units cc ON cc.id = m.cost_center_id WHERE m.facility_id = ? ORDER BY m.id""",
                (rs, i) -> new Machine(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), null), facId)) {
            machines.add(new Machine(m.id(), m.name(), m.location(), m.costCenter(),
                    sql.list(SLOT_SQL + " WHERE v.machine_id = ? ORDER BY v.slot", KeepStockController::mapSlot, m.id())));
        }
        List<Event> events = sql.list("""
                SELECT e.id, e.machine_id, e.slot, e.badge_id, u.name, e.sku, e.qty, e.outcome, e.message, e.created_at
                FROM keepstock.vending_events e JOIN keepstock.vending_machines m ON m.id = e.machine_id
                LEFT JOIN accounts.users u ON u.id = e.user_id WHERE m.facility_id = ? ORDER BY e.id DESC LIMIT 25""",
                (rs, i) -> new Event(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getInt(7), rs.getString(8), rs.getString(9), Sql.instant(rs, 10)), facId);
        List<Checkout.OrderSummary> orders = sql.list(Checkout.ORDER_SUMMARY_SQL
                + " WHERE o.source = 'keepstock' AND o.facility_id = ? ORDER BY o.created_at DESC LIMIT 15", Checkout::mapOrderSummary, facId);
        List<Badge> badges = sql.list("SELECT badge_id, name FROM accounts.users WHERE badge_id IS NOT NULL ORDER BY badge_id",
                (rs, i) -> new Badge(rs.getString(1), rs.getString(2)));
        return obj("facilityId", facId, "facilities", facilities, "bins", bins, "machines", machines,
                "events", events, "orders", orders, "badges", badges);
    }

    public record ReplenishResult(String orderId, String costCenter, int lines, double total, int shipments) {}

    /** Raises one KeepStock order for a cost center and marks the bins/slots as on order. */
    private ReplenishResult replenish(Accounts.User u, String costCenterId, List<Checkout.LineReq> lines) {
        Checkout.OrderReq req = new Checkout.OrderReq();
        req.lines = lines;
        req.costCenterId = costCenterId;
        req.poNumber = "KEEPSTOCK-AUTO";
        req.deliveryMethod = "ship";
        req.fulfillmentPref = "ship_available";
        req.source = "keepstock";
        Checkout.Placed placed = checkout.placeOrderTx(u, req);
        for (Checkout.LineReq l : lines) {
            sql.exec("UPDATE keepstock.bins SET on_order = on_order + ? WHERE 'bin:' || id = ?", l.qty, l.ref);
            sql.exec("UPDATE keepstock.vending_slots SET on_order = on_order + ? WHERE 'slot:' || machine_id || '/' || slot = ?", l.qty, l.ref);
        }
        Checkout.Evaluation ev = placed.evaluation();
        return new ReplenishResult(placed.orderId(), ev.costCenter.get("code") + " " + ev.costCenter.get("name"), lines.size(),
                ev.total, ev.plan.shipments.size());
    }

    public static final class ScanBody {
        public Integer onHand;
    }

    @PostMapping("/bins/{id}/scan")
    public Map<String, Object> scan(Accounts.User u, @PathVariable("id") String binId, HttpServletRequest r) {
        ScanBody body = bodies.json(r, ScanBody.class, ScanBody::new);
        if (body.onHand == null || body.onHand < 0) {
            throw ApiException.badRequest("onHand must be a non-negative count");
        }
        return tx.execute(status -> {
            Bin b = sql.one(BIN_SQL + " WHERE b.id = ? FOR UPDATE OF b", KeepStockController::mapBin, binId);
            facilityInCorp(b.facilityId, u);
            b.onHand = body.onHand;
            sql.exec("UPDATE keepstock.bins SET on_hand = ?, last_scanned_at = now() WHERE id = ?", b.onHand, b.id);
            ReplenishResult order = null;
            String msg = "Counted " + b.onHand + "; reorder point is " + b.minQty + ".";
            if (b.onHand <= b.minQty && b.onOrder == 0) {
                int qty = b.maxQty - b.onHand;
                order = replenish(u, b.costCenterId, List.of(new Checkout.LineReq(b.sku, qty, "bin:" + b.id)));
                b.onOrder = qty;
                msg += " At or below min: replenishment " + order.orderId() + " raised for " + qty + " to restore max " + b.maxQty + ".";
            } else if (b.onHand <= b.minQty) {
                msg += " Already " + b.onOrder + " on order; no duplicate replenishment.";
            }
            return obj("bin", b, "order", order, "message", msg);
        });
    }

    public static final class DispenseBody {
        public String slot = "";
        public String badgeId = "";
        public int qty;
    }

    @PostMapping("/machines/{id}/dispense")
    public Map<String, Object> dispense(Accounts.User u, @PathVariable("id") String machineId, HttpServletRequest r) {
        DispenseBody body = bodies.json(r, DispenseBody.class, DispenseBody::new);
        int qty = Math.max(body.qty, 1);

        return tx.execute(status -> {
            Slot sl = sql.one(SLOT_SQL + " WHERE v.machine_id = ? AND v.slot = ? FOR UPDATE OF v", KeepStockController::mapSlot,
                    machineId, body.slot);
            String facId = sql.one("SELECT facility_id FROM keepstock.vending_machines WHERE id = ?", (rs, i) -> rs.getString(1), machineId);
            String machineCorp = inventory.loadFacility(facId).corporationId();

            // The badge identifies the employee, whose cost center is charged.
            Accounts.User badgeUser = sql.maybe("SELECT id FROM accounts.users WHERE badge_id = ?", (rs, i) -> rs.getString(1), body.badgeId)
                    .map(accounts::loadUser).orElse(null);
            String outcome = "dispensed";
            String msg = "";
            if (badgeUser == null) {
                outcome = "denied";
                msg = "Badge not recognised.";
            } else if (!badgeUser.corporationId().equals(machineCorp)) {
                outcome = "denied";
                msg = "Badge belongs to another company.";
            } else {
                int used = sql.one("""
                        SELECT coalesce(sum(qty), 0) FROM keepstock.vending_events
                        WHERE badge_id = ? AND machine_id = ? AND slot = ? AND outcome = 'dispensed' AND created_at >= date_trunc('day', now())""",
                        (rs, i) -> rs.getInt(1), body.badgeId, machineId, body.slot);
                if (used + qty > sl.dailyLimit) {
                    outcome = "denied";
                    msg = "Daily limit is " + sl.dailyLimit + " per person; " + badgeUser.name() + " already took " + used + " today.";
                } else if (sl.onHand < qty) {
                    outcome = "denied";
                    msg = "Slot is empty.";
                } else {
                    String cc = sql.one("SELECT code || ' ' || name FROM accounts.org_units WHERE id = ?", (rs, i) -> rs.getString(1),
                            badgeUser.costCenterId());
                    sl.onHand -= qty;
                    msg = "Dispensed " + qty + " x " + sl.sku + " to " + badgeUser.name() + "; charged to cost center " + cc + ".";
                    sql.exec("UPDATE keepstock.vending_slots SET on_hand = ? WHERE machine_id = ? AND slot = ?", sl.onHand, machineId, body.slot);
                }
            }

            ReplenishResult order = null;
            if (outcome.equals("dispensed") && sl.onHand <= sl.par && sl.onOrder == 0) {
                String cc = sql.one("SELECT cost_center_id FROM keepstock.vending_machines WHERE id = ?", (rs, i) -> rs.getString(1), machineId);
                int refill = sl.capacity - sl.onHand;
                order = replenish(badgeUser, cc, List.of(new Checkout.LineReq(sl.sku, refill, "slot:" + machineId + "/" + sl.slot)));
                sl.onOrder = refill;
                msg += " Slot reached par " + sl.par + ": replenishment " + order.orderId() + " raised for " + refill + ".";
            }
            sql.exec("""
                    INSERT INTO keepstock.vending_events (machine_id, slot, badge_id, user_id, sku, qty, outcome, message)
                    VALUES (?,?,?,?,?,?,?,?)""",
                    machineId, body.slot, body.badgeId, badgeUser == null ? null : badgeUser.id(), sl.sku, qty, outcome, msg);
            return obj("outcome", outcome, "message", msg, "slot", sl, "order", order);
        });
    }

    public static final class ReplenishBody {
        public String facilityId = "";
    }

    private record Need(String costCenter, Checkout.LineReq line) {}

    /**
     * Sweeps every bin and slot at or below its reorder point and raises one
     * consolidated order per cost center.
     */
    @PostMapping("/replenish")
    public Map<String, Object> replenishAll(Accounts.User u, HttpServletRequest r) {
        ReplenishBody body = bodies.json(r, ReplenishBody.class, ReplenishBody::new);
        return tx.execute(status -> {
            facilityInCorp(body.facilityId, u);
            List<Need> needs = sql.list("""
                    SELECT cost_center_id, sku, max_qty - on_hand, 'bin:' || id FROM keepstock.bins
                    WHERE facility_id = ? AND on_hand <= min_qty AND on_order = 0
                    UNION ALL
                    SELECT m.cost_center_id, v.sku, v.capacity - v.on_hand, 'slot:' || v.machine_id || '/' || v.slot
                    FROM keepstock.vending_slots v JOIN keepstock.vending_machines m ON m.id = v.machine_id
                    WHERE m.facility_id = ? AND v.on_hand <= v.par AND v.on_order = 0
                    ORDER BY 1, 4""",
                    (rs, i) -> new Need(rs.getString(1), new Checkout.LineReq(rs.getString(2), rs.getInt(3), rs.getString(4))),
                    body.facilityId, body.facilityId);
            Map<String, List<Checkout.LineReq>> byCc = new LinkedHashMap<>();
            for (Need n : needs) {
                byCc.computeIfAbsent(n.costCenter(), k -> new ArrayList<>()).add(n.line());
            }
            List<ReplenishResult> results = new ArrayList<>();
            byCc.forEach((cc, lines) -> results.add(replenish(u, cc, lines)));
            return obj("orders", results, "linesConsidered", needs.size());
        });
    }
}
