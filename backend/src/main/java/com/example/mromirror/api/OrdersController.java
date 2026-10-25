package com.example.mromirror.api;

import com.example.mromirror.governance.Governance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.mromirror.api.Json.obj;
import static com.example.mromirror.support.Go.fixed;

@RestController
@RequestMapping("/api/orders")
public class OrdersController {
    private static final TypeReference<List<Checkout.ApprovalEntry>> HISTORY = new TypeReference<>() {};

    private final Sql sql;
    private final Accounts accounts;
    private final Checkout checkout;
    private final Bodies bodies;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public OrdersController(Sql sql, Accounts accounts, Checkout checkout, Bodies bodies, Clock clock, ObjectMapper mapper,
                            TransactionTemplate tx) {
        this.sql = sql;
        this.accounts = accounts;
        this.checkout = checkout;
        this.bodies = bodies;
        this.clock = clock;
        this.mapper = mapper;
        this.tx = tx;
    }

    @PostMapping("/preview")
    public Checkout.Evaluation preview(Accounts.User u, HttpServletRequest r) {
        Checkout.OrderReq req = bodies.json(r, Checkout.OrderReq.class, Checkout.OrderReq::new);
        req.source = "web";
        return checkout.evaluate(u, req);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(Accounts.User u, HttpServletRequest r) {
        Checkout.OrderReq req = bodies.json(r, Checkout.OrderReq.class, Checkout.OrderReq::new);
        req.source = "web";
        Checkout.Placed placed = checkout.placeOrder(u, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(obj("orderId", placed.orderId(), "evaluation", placed.evaluation()));
    }

    @GetMapping
    public List<Checkout.OrderSummary> list(Accounts.User u, HttpServletRequest r) {
        String scope = Optional.ofNullable(r.getParameter("scope")).orElse("");
        String where = switch (scope) {
            case "approvals" -> " WHERE o.approver_id = ? AND o.status = 'pending_approval'";
            case "corporation" -> " WHERE o.corporation_id = (SELECT corporation_id FROM accounts.users WHERE id = ?)";
            default -> " WHERE o.user_id = ?";
        };
        return sql.list(Checkout.ORDER_SUMMARY_SQL + where + " ORDER BY o.created_at DESC LIMIT 200", Checkout::mapOrderSummary, u.id());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(Accounts.User u, @PathVariable("id") String id) {
        return checkout.loadOrderDetail(id);
    }

    // ---------------------------------------------------------------- approvals

    public static final class NoteBody {
        public String note = "";
    }

    private record Pending(String status, String approverId, double total, String approvals) {}

    private record Locked(double total, List<Checkout.ApprovalEntry> history) {}

    private Locked lockPending(String id, Accounts.User u) {
        Pending p = sql.maybe("SELECT status, approver_id, total, approvals FROM oms.orders WHERE id = ? FOR UPDATE",
                        (rs, i) -> new Pending(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getString(4)), id)
                .orElseThrow(() -> ApiException.notFound("no order " + id));
        if (!p.status().equals("pending_approval")) {
            throw ApiException.conflict("order is " + p.status());
        }
        if (p.approverId() == null || !p.approverId().equals(u.id())) {
            throw ApiException.forbidden("order is waiting on a different approver");
        }
        List<Checkout.ApprovalEntry> hist;
        try {
            hist = new ArrayList<>(mapper.readValue(p.approvals(), HISTORY));
        } catch (Exception e) {
            hist = new ArrayList<>();
        }
        return new Locked(p.total(), hist);
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(Accounts.User u, @PathVariable("id") String id, HttpServletRequest r) {
        NoteBody body = bodies.jsonOrEmpty(r, NoteBody.class, NoteBody::new);
        tx.executeWithoutResult(status -> {
            Locked l = lockPending(id, u);
            List<Checkout.ApprovalEntry> hist = l.history();
            // An approver can only sign within their own authority; above it the
            // requisition escalates to the next approver who can.
            if (l.total() > u.perOrderLimit()) {
                Governance.Approver next = Governance.firstApprover(accounts.approverChain(u.id()), l.total())
                        .orElseThrow(() -> ApiException.conflict("no approver above you has authority for this order"));
                hist.add(new Checkout.ApprovalEntry(u.id(), u.name(), "escalated",
                        "Approved up to $" + fixed(u.perOrderLimit(), 0) + " authority; escalated to " + next.name() + ". " + body.note,
                        clock.now().toInstant()));
                sql.exec("UPDATE oms.orders SET approver_id = ?, approvals = ?::jsonb WHERE id = ?", next.id(), json(hist), id);
            } else {
                hist.add(new Checkout.ApprovalEntry(u.id(), u.name(), "approved", body.note, clock.now().toInstant()));
                sql.exec("UPDATE oms.orders SET approvals = ?::jsonb WHERE id = ?", json(hist), id);
                checkout.release(id);
            }
        });
        return checkout.loadOrderDetail(id);
    }

    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(Accounts.User u, @PathVariable("id") String id, HttpServletRequest r) {
        NoteBody body = bodies.jsonOrEmpty(r, NoteBody.class, NoteBody::new);
        tx.executeWithoutResult(status -> {
            List<Checkout.ApprovalEntry> hist = lockPending(id, u).history();
            hist.add(new Checkout.ApprovalEntry(u.id(), u.name(), "rejected", body.note, clock.now().toInstant()));
            sql.exec("UPDATE oms.orders SET status = 'rejected', approvals = ?::jsonb WHERE id = ?", json(hist), id);
        });
        return checkout.loadOrderDetail(id);
    }

    /**
     * Simulates the warehouses picking and shipping a released order: reserved
     * stock leaves on-hand, and KeepStock replenishment refills its bins.
     */
    @PostMapping("/{id}/ship")
    public Map<String, Object> ship(Accounts.User u, @PathVariable("id") String id) {
        tx.executeWithoutResult(status -> {
            String st = sql.one("SELECT status FROM oms.orders WHERE id = ? FOR UPDATE", (rs, i) -> rs.getString(1), id);
            if (!st.equals("released")) {
                throw ApiException.conflict("only released orders can ship; order is " + st);
            }
            sql.exec("""
                    UPDATE oms.inventory i SET on_hand = i.on_hand - x.qty, reserved = i.reserved - x.qty
                    FROM (SELECT s.node_id, sl.sku, sum(sl.qty) AS qty FROM oms.shipments s
                          JOIN oms.shipment_lines sl ON sl.shipment_id = s.id
                          WHERE s.order_id = ? AND s.node_id IS NOT NULL AND s.status = 'released'
                          GROUP BY s.node_id, sl.sku) x
                    WHERE i.node_id = x.node_id AND i.sku = x.sku""", id);
            // Backordered quantities are treated as arriving with the shipment in this simulation.
            sql.exec("UPDATE oms.shipments SET status = 'shipped' WHERE order_id = ?", id);
            sql.exec("""
                    UPDATE keepstock.bins b SET on_hand = b.on_hand + l.qty, on_order = greatest(b.on_order - l.qty, 0)
                    FROM oms.order_lines l WHERE l.order_id = ? AND l.ref = 'bin:' || b.id""", id);
            sql.exec("""
                    UPDATE keepstock.vending_slots v SET on_hand = least(v.on_hand + l.qty, v.capacity), on_order = greatest(v.on_order - l.qty, 0)
                    FROM oms.order_lines l WHERE l.order_id = ? AND l.ref = 'slot:' || v.machine_id || '/' || v.slot""", id);
            sql.exec("UPDATE oms.orders SET status = 'shipped' WHERE id = ?", id);
        });
        return checkout.loadOrderDetail(id);
    }
}
