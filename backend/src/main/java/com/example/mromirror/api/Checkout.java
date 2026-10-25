package com.example.mromirror.api;

import com.example.mromirror.governance.Governance;
import com.example.mromirror.pricing.Pricing;
import com.example.mromirror.sourcing.Sourcing;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.mromirror.api.Json.obj;
import static com.example.mromirror.support.Go.fixed;

/**
 * The checkout pipeline shared by web checkout, PunchOut, EDI and KeepStock,
 * plus order persistence, release to the warehouse and order reads.
 */
@Component
public class Checkout {

    public static final class LineReq {
        public String sku = "";
        public int qty;
        /** Links a KeepStock replenishment line to its bin or vending slot. */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public String ref = "";

        public LineReq() {}

        public LineReq(String sku, int qty, String ref) {
            this.sku = sku;
            this.qty = qty;
            this.ref = ref;
        }
    }

    public static final class OrderReq {
        public List<LineReq> lines = new ArrayList<>();
        public String costCenterId = "";
        public String poNumber = "";
        public String blanketPoId = "";
        public String deliveryMethod = "";
        public String fulfillmentPref = "";
        @JsonIgnore
        public String source = "";

        void applyDefaults() {
            if (deliveryMethod.isEmpty()) {
                deliveryMethod = "ship";
            }
            if (fulfillmentPref.isEmpty()) {
                fulfillmentPref = "ship_available";
            }
        }

        /** The lines, with a JSON null line read as an empty one. */
        List<LineReq> lines() {
            List<LineReq> out = new ArrayList<>();
            for (LineReq l : lines) {
                out.add(l == null ? new LineReq() : l);
            }
            return out;
        }
    }

    public record PricedLine(int lineNo, String sku, String name, String brand, String uom, int qty, double unitPrice,
                             double extended, Pricing.Result price, boolean hazmat, boolean dropShipOnly,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Governance.Restriction restriction,
                             @JsonInclude(JsonInclude.Include.NON_EMPTY) String ref) {}

    public static final class Evaluation {
        public List<PricedLine> lines = new ArrayList<>();
        public double subtotal;
        public double taxRate;
        public double tax;
        public String taxNote = "";
        public double total;
        public Governance.Result governance;
        public Sourcing.Plan plan;
        public Map<String, String> facility;
        public Map<String, String> costCenter;
        public double mtdSpend;

        @JsonIgnore
        Inventory.Facility fac;
        @JsonIgnore
        String costCenterId;
        @JsonIgnore
        String blanketPoId;
    }

    /** Governance blocked the order; the evaluation says why. */
    public static final class BlockedException extends ApiException {
        private final transient Evaluation evaluation;

        BlockedException(Evaluation evaluation) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, "order blocked by purchasing governance");
            this.evaluation = evaluation;
        }

        public Evaluation evaluation() {
            return evaluation;
        }
    }

    public record Placed(String orderId, Evaluation evaluation) {}

    public record OrderSummary(String id, String userId, String userName, String costCenter, String facility,
                               String poNumber, String source, String status, double total, String approverId,
                               String approverName, Instant createdAt, int lineCount, String deliveryMethod) {}

    static final String ORDER_SUMMARY_SQL = """
            SELECT o.id, o.user_id, u.name, cc.code || ' ' || cc.name, f.name, o.po_number, o.source, o.status, o.total,
                o.approver_id, a.name, o.created_at, (SELECT count(*) FROM oms.order_lines l WHERE l.order_id = o.id), o.delivery_method
            FROM oms.orders o
            JOIN accounts.users u ON u.id = o.user_id
            JOIN accounts.org_units cc ON cc.id = o.cost_center_id
            JOIN accounts.org_units f ON f.id = o.facility_id
            LEFT JOIN accounts.users a ON a.id = o.approver_id""";

    static OrderSummary mapOrderSummary(ResultSet rs, int row) throws SQLException {
        return new OrderSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getDouble(9), rs.getString(10), rs.getString(11),
                Sql.instant(rs, 12), rs.getInt(13), rs.getString(14));
    }

    public record ShipmentOut(int id, String nodeId, String nodeName, String method, int etaDays, String etaLabel,
                              String status, String note, List<Sourcing.Allocation> lines) {}

    public record LineOut(int lineNo, String sku, String name, String uom, int qty, double unitPrice,
                          String priceSource, double extended, String ref) {}

    /** action: approved | escalated | rejected */
    public record ApprovalEntry(String by, String byName, String action, String note, Instant at) {}

    private final Sql sql;
    private final Accounts accounts;
    private final Catalog catalog;
    private final Inventory inventory;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public Checkout(Sql sql, Accounts accounts, Catalog catalog, Inventory inventory, Clock clock, ObjectMapper mapper,
                    TransactionTemplate tx) {
        this.sql = sql;
        this.accounts = accounts;
        this.catalog = catalog;
        this.inventory = inventory;
        this.clock = clock;
        this.mapper = mapper;
        this.tx = tx;
    }

    private record CostCenterRow(String name, String code, String facilityId) {}

    private record Exemption(String cert, String type, LocalDate expires) {}

    /**
     * Prices every line (Pricing), computes tax with exemptions (Accounts), runs
     * purchasing governance (Accounts/Procurement) and plans sourcing against
     * live ATP (OMS). It has no side effects.
     */
    public Evaluation evaluate(Accounts.User u, OrderReq req) {
        List<LineReq> lines = req.lines();
        if (lines.isEmpty()) {
            throw ApiException.badRequest("order has no lines");
        }
        req.applyDefaults();
        if (!req.deliveryMethod.equals("ship") && !req.deliveryMethod.equals("will_call")) {
            throw ApiException.badRequest("deliveryMethod must be ship or will_call");
        }
        if (!req.fulfillmentPref.equals("ship_available") && !req.fulfillmentPref.equals("ship_complete")) {
            throw ApiException.badRequest("fulfillmentPref must be ship_available or ship_complete");
        }
        String costCenterId = req.costCenterId.isEmpty() ? u.costCenterId() : req.costCenterId;

        CostCenterRow cc = sql.maybe("SELECT name, code, parent_id FROM accounts.org_units WHERE id = ? AND kind = 'cost_center'",
                        (rs, i) -> new CostCenterRow(rs.getString(1), rs.getString(2), rs.getString(3)), costCenterId)
                .orElseThrow(() -> ApiException.badRequest("unknown cost center " + costCenterId));
        Inventory.Facility fac = inventory.loadFacility(cc.facilityId());
        if (!fac.corporationId().equals(u.corporationId())) {
            throw ApiException.forbidden("cost center belongs to another corporation");
        }

        List<String> skus = new ArrayList<>();
        for (LineReq l : lines) {
            if (l.qty <= 0) {
                throw ApiException.badRequest("quantity must be positive for " + l.sku);
            }
            skus.add(l.sku);
        }
        Map<String, Catalog.Product> prods = catalog.loadProducts(skus);
        Catalog.PriceBook book = catalog.loadPriceBook(u.corporationId());

        Evaluation ev = new Evaluation();
        ev.fac = fac;
        ev.costCenterId = costCenterId;
        ev.facility = strings("id", fac.id(), "name", fac.name(), "state", fac.state());
        ev.costCenter = strings("id", costCenterId, "name", cc.name(), "code", cc.code());
        List<Governance.Line> govLines = new ArrayList<>();
        List<Sourcing.Line> srcLines = new ArrayList<>();
        double subtotal = 0;
        for (int i = 0; i < lines.size(); i++) {
            LineReq l = lines.get(i);
            Catalog.Product p = prods.get(l.sku);
            if (p == null) {
                throw ApiException.badRequest("unknown SKU " + l.sku);
            }
            Pricing.Result res = book.quote(p, l.qty);
            PricedLine pl = new PricedLine(i + 1, p.sku(), p.name(), p.brand(), p.uom(), l.qty, res.unitPrice(),
                    res.extended(), res, p.hazmat(), p.dropShipOnly(), book.restriction(p.categoryId()), l.ref);
            ev.lines.add(pl);
            subtotal += res.extended();
            govLines.add(new Governance.Line(p.sku(), p.name(), p.status().equals("discontinued"), pl.restriction()));
            srcLines.add(new Sourcing.Line(p.sku(), l.qty, p.hazmat(), p.dropShipOnly()));
        }
        ev.subtotal = Pricing.round2(subtotal);

        // Tax: state rate unless the corporation holds a current exemption certificate for that state.
        double rate = sql.one("SELECT coalesce((SELECT rate FROM pricing.tax_rates WHERE state = ?), 0)::float8",
                (rs, i) -> rs.getDouble(1), fac.state());
        Optional<Exemption> exemption = sql.maybe("""
                SELECT certificate_number, exemption_type, expires_on FROM accounts.tax_exemptions
                WHERE corporation_id = ? AND state = ? ORDER BY expires_on DESC LIMIT 1""",
                (rs, i) -> new Exemption(rs.getString(1), rs.getString(2), Sql.date(rs, 3)), u.corporationId(), fac.state());
        if (exemption.isPresent() && !clock.today().isAfter(exemption.get().expires())) {
            ev.taxNote = "Exempt: " + exemption.get().cert() + " (" + exemption.get().type() + ")";
        } else if (exemption.isPresent()) {
            ev.taxRate = rate;
            ev.taxNote = fac.state() + " " + fixed(rate * 100, 2) + "% (exemption " + exemption.get().cert() + " expired "
                    + exemption.get().expires() + ")";
        } else {
            ev.taxRate = rate;
            ev.taxNote = fac.state() + " " + fixed(rate * 100, 2) + "%";
        }
        ev.tax = Pricing.round2(ev.subtotal * ev.taxRate);
        ev.total = Pricing.round2(ev.subtotal + ev.tax);

        // Governance
        List<Governance.Approver> chain = accounts.approverChain(u.id());
        ev.mtdSpend = accounts.mtdSpend(u.id());
        Governance.BlanketPO bpo = null;
        if (!req.blanketPoId.isEmpty()) {
            bpo = sql.maybe("""
                            SELECT po_number, facility_id, amount_authorized, amount_consumed, valid_from, valid_to
                            FROM accounts.blanket_pos WHERE id = ? AND corporation_id = ?""",
                    (rs, i) -> new Governance.BlanketPO(rs.getString(1), rs.getString(2) == null ? "" : rs.getString(2),
                            rs.getDouble(3), rs.getDouble(4), Sql.date(rs, 5), Sql.date(rs, 6)),
                    req.blanketPoId, u.corporationId()).orElseThrow(() -> ApiException.badRequest("unknown blanket PO " + req.blanketPoId));
            ev.blanketPoId = req.blanketPoId;
        }
        ev.governance = Governance.evaluate(new Governance.Input(
                new Governance.Buyer(u.id(), u.perOrderLimit(), u.monthlyLimit(), ev.mtdSpend, u.certifications(), chain),
                govLines, ev.total, fac.id(), req.poNumber, bpo, req.source, clock.today()));

        // Sourcing
        ev.plan = Sourcing.build(new Sourcing.Request(srcLines, inventory.loadNodes(), inventory.loadATP(skus, false),
                fac.lat(), fac.lon(), req.deliveryMethod, req.fulfillmentPref));
        return ev;
    }

    private static Map<String, String> strings(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /**
     * Evaluates and persists an order in one transaction. Blocked orders are
     * refused; auto-approved orders are released to the warehouse immediately;
     * the rest wait in the approver's queue.
     */
    public Placed placeOrder(Accounts.User u, OrderReq req) {
        return tx.execute(status -> placeOrderTx(u, req));
    }

    /** placeOrder inside the caller's transaction. */
    public Placed placeOrderTx(Accounts.User u, OrderReq req) {
        req.applyDefaults();
        Evaluation ev = evaluate(u, req);
        if (ev.governance.outcome().equals(Governance.BLOCKED)) {
            throw new BlockedException(ev);
        }
        long seq = sql.one("SELECT nextval('oms.order_seq')", (rs, i) -> rs.getLong(1));
        String id = "ORD-" + seq;
        String status = "released";
        String approverId = null;
        if (ev.governance.outcome().equals(Governance.APPROVAL_REQUIRED)) {
            status = "pending_approval";
            approverId = ev.governance.approver().id();
        }
        sql.exec("""
                INSERT INTO oms.orders (id, user_id, corporation_id, facility_id, cost_center_id, po_number, blanket_po_id,
                    source, status, delivery_method, fulfillment_pref, subtotal, tax, total, tax_note, approver_id, governance)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)""",
                id, u.id(), u.corporationId(), ev.fac.id(), ev.costCenterId, req.poNumber, ev.blanketPoId, req.source, status,
                req.deliveryMethod, req.fulfillmentPref, ev.subtotal, ev.tax, ev.total, ev.taxNote, approverId,
                catalog.toJson(ev.governance.checks()));
        for (PricedLine l : ev.lines) {
            sql.exec("""
                    INSERT INTO oms.order_lines (order_id, line_no, sku, qty, unit_price, price_source, extended, ref)
                    VALUES (?,?,?,?,?,?,?,?)""",
                    id, l.lineNo(), l.sku(), l.qty(), l.unitPrice(), l.price().label(), l.extended(),
                    l.ref() == null || l.ref().isEmpty() ? null : l.ref());
        }
        if (status.equals("released")) {
            release(id);
        }
        return new Placed(id, ev);
    }

    private record ReleaseRow(String facilityId, String method, String pref, double total, String blanketPoId) {}

    /**
     * Re-plans sourcing against locked inventory, reserves stock, writes
     * shipments and draws down the blanket PO. Runs inside the caller's transaction.
     */
    public void release(String orderId) {
        ReleaseRow o = sql.one("SELECT facility_id, delivery_method, fulfillment_pref, total, blanket_po_id FROM oms.orders WHERE id = ?",
                (rs, i) -> new ReleaseRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getString(5)),
                orderId);
        List<Sourcing.Line> lines = sql.list("""
                SELECT l.sku, l.qty, p.hazmat_un IS NOT NULL, p.drop_ship_only FROM oms.order_lines l
                JOIN pim.products p ON p.sku = l.sku WHERE l.order_id = ? ORDER BY l.line_no""",
                (rs, i) -> new Sourcing.Line(rs.getString(1), rs.getInt(2), rs.getBoolean(3), rs.getBoolean(4)), orderId);
        List<String> skus = lines.stream().map(Sourcing.Line::sku).toList();
        Inventory.Facility fac = inventory.loadFacility(o.facilityId());
        List<Sourcing.Node> nodes = inventory.loadNodes();
        Map<String, Map<String, Integer>> atp = inventory.loadATP(skus, true);
        Sourcing.Plan plan = Sourcing.build(new Sourcing.Request(lines, nodes, atp, fac.lat(), fac.lon(), o.method(), o.pref()));

        for (Sourcing.Shipment sh : plan.shipments) {
            int shipId = sql.one("""
                    INSERT INTO oms.shipments (order_id, node_id, method, eta_days, eta_label, note)
                    VALUES (?,?,?,?,?,?) RETURNING id""",
                    (rs, i) -> rs.getInt(1), orderId, sh.nodeId, sh.method, sh.etaDays, sh.etaLabel, sh.note);
            for (Sourcing.Allocation a : sh.lines) {
                sql.exec("INSERT INTO oms.shipment_lines (shipment_id, sku, qty) VALUES (?,?,?)", shipId, a.sku(), a.qty());
                sql.exec("UPDATE oms.inventory SET reserved = reserved + ? WHERE node_id = ? AND sku = ?", a.qty(), sh.nodeId, a.sku());
            }
        }
        if (!plan.backorders.isEmpty()) {
            int shipId = sql.one("""
                    INSERT INTO oms.shipments (order_id, node_id, method, eta_days, eta_label, status, note)
                    VALUES (?, NULL, 'backorder', ?, ?, 'backordered', 'awaiting replenishment from supplier') RETURNING id""",
                    (rs, i) -> rs.getInt(1), orderId, plan.backorderEtaDays,
                    "Backordered, ~" + plan.backorderEtaDays + " business days");
            for (Sourcing.Allocation a : plan.backorders) {
                sql.exec("INSERT INTO oms.shipment_lines (shipment_id, sku, qty) VALUES (?,?,?)", shipId, a.sku(), a.qty());
            }
        }
        if (o.blanketPoId() != null) {
            sql.exec("UPDATE accounts.blanket_pos SET amount_consumed = amount_consumed + ? WHERE id = ?", o.total(), o.blanketPoId());
        }
        sql.exec("UPDATE oms.orders SET status = 'released' WHERE id = ?", orderId);
    }

    private record OrderExtra(double subtotal, double tax, String taxNote, String pref, String governance, String approvals) {}

    public Map<String, Object> loadOrderDetail(String id) {
        OrderSummary o = sql.maybe(ORDER_SUMMARY_SQL + " WHERE o.id = ?", Checkout::mapOrderSummary, id)
                .orElseThrow(() -> ApiException.notFound("no order " + id));
        OrderExtra x = sql.one("SELECT subtotal, tax, tax_note, fulfillment_pref, governance, approvals FROM oms.orders WHERE id = ?",
                (rs, i) -> new OrderExtra(rs.getDouble(1), rs.getDouble(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)), id);
        List<LineOut> lines = sql.list("""
                SELECT l.line_no, l.sku, p.name, p.uom, l.qty, l.unit_price, l.price_source, l.extended, l.ref
                FROM oms.order_lines l JOIN pim.products p ON p.sku = l.sku WHERE l.order_id = ? ORDER BY l.line_no""",
                (rs, i) -> new LineOut(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getDouble(6), rs.getString(7), rs.getDouble(8), rs.getString(9)), id);
        List<ShipmentOut> shipments = new ArrayList<>();
        for (ShipmentOut sh : sql.list("""
                SELECT s.id, s.node_id, n.name, s.method, s.eta_days, s.eta_label, s.status, s.note
                FROM oms.shipments s LEFT JOIN oms.nodes n ON n.id = s.node_id WHERE s.order_id = ? ORDER BY s.eta_days, s.id""",
                (rs, i) -> new ShipmentOut(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), null), id)) {
            List<Sourcing.Allocation> alloc = sql.list("SELECT sku, qty FROM oms.shipment_lines WHERE shipment_id = ?",
                    (rs, i) -> new Sourcing.Allocation(rs.getString(1), rs.getInt(2)), sh.id());
            shipments.add(new ShipmentOut(sh.id(), sh.nodeId(), sh.nodeName(), sh.method(), sh.etaDays(), sh.etaLabel(),
                    sh.status(), sh.note(), alloc));
        }
        return obj("order", o, "subtotal", x.subtotal(), "tax", x.tax(), "taxNote", x.taxNote(), "fulfillmentPref", x.pref(),
                "governance", readTree(x.governance()), "approvals", readTree(x.approvals()), "lines", lines,
                "shipments", shipments);
    }

    JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
