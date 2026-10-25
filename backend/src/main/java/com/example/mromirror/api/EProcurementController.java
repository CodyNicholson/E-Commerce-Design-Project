package com.example.mromirror.api;

import com.example.mromirror.cxml.Cxml;
import com.example.mromirror.edi.Edi;
import com.example.mromirror.governance.Governance;
import com.example.mromirror.sourcing.Sourcing;
import com.example.mromirror.support.Go;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.example.mromirror.api.Json.obj;

@RestController
@RequestMapping("/api")
public class EProcurementController {
    private static final String OUR_ISA = "MROMIRROR";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Sql sql;
    private final Accounts accounts;
    private final Catalog catalog;
    private final Checkout checkout;
    private final Bodies bodies;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String publicUrl;

    public EProcurementController(Sql sql, Accounts accounts, Catalog catalog, Checkout checkout, Bodies bodies, Clock clock,
                                  TransactionTemplate tx, @Value("${mromirror.public-url:}") String publicUrl) {
        this.sql = sql;
        this.accounts = accounts;
        this.catalog = catalog;
        this.checkout = checkout;
        this.bodies = bodies;
        this.clock = clock;
        this.tx = tx;
        this.publicUrl = (publicUrl.isEmpty() ? "http://localhost:8090" : publicUrl).replaceAll("/+$", "");
    }

    private static String randomHex(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static ResponseEntity<String> xml(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_XML).body(body);
    }

    // ---------------------------------------------------------------- PunchOut

    public record PunchoutIdentity(String identity, String erp, String corporationId, String corporation, String defaultUserId,
                                   String defaultEmail, @JsonIgnore String sharedSecret) {}

    private List<PunchoutIdentity> loadIdentities() {
        return sql.list("""
                SELECT i.identity, i.erp_name, i.corporation_id, c.name, i.default_user_id, u.email, i.shared_secret
                FROM procurement.punchout_identities i
                JOIN accounts.org_units c ON c.id = i.corporation_id
                JOIN accounts.users u ON u.id = i.default_user_id ORDER BY i.identity""",
                (rs, i) -> new PunchoutIdentity(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)));
    }

    @GetMapping("/punchout/identities")
    public List<PunchoutIdentity> identities() {
        return loadIdentities();
    }

    /**
     * Plays the buyer's ERP: returns the setup request that ERP would post,
     * shared secret included, so the flow can be exercised.
     */
    @GetMapping("/punchout/sample")
    public ResponseEntity<String> sample(HttpServletRequest r) {
        String want = Optional.ofNullable(r.getParameter("identity")).orElse("");
        String email = Optional.ofNullable(r.getParameter("email")).orElse("");
        for (PunchoutIdentity id : loadIdentities()) {
            if (id.identity().equals(want) || want.isEmpty()) {
                String doc = Cxml.sampleSetupRequest(id.identity(), id.sharedSecret(), id.erp(),
                        email.isEmpty() ? id.defaultEmail() : email,
                        "COOKIE-" + randomHex(4).toUpperCase(Locale.ROOT), publicUrl + "/erp/return", clock.now());
                return xml(HttpStatus.OK, doc);
            }
        }
        throw ApiException.notFound("no PunchOut identity " + want);
    }

    private record IdentityRow(String secret, String corpId, String defaultUser) {}

    @PostMapping("/punchout/setup")
    public ResponseEntity<String> setup(HttpServletRequest r) {
        String body = bodies.text(r);
        String payload = clock.now().toEpochSecond() + "." + randomHex(4) + "@mromirror";

        Cxml.SetupRequest req;
        try {
            req = Cxml.parseSetupRequest(body);
        } catch (Cxml.InvalidCxmlException e) {
            return xml(HttpStatus.BAD_REQUEST, Cxml.buildSetupResponse(payload, clock.now(), 400, e.getMessage(), ""));
        }
        Optional<IdentityRow> ident = sql.maybe(
                "SELECT shared_secret, corporation_id, default_user_id FROM procurement.punchout_identities WHERE identity = ?",
                (rs, i) -> new IdentityRow(rs.getString(1), rs.getString(2), rs.getString(3)), req.fromIdentity());
        if (ident.isEmpty() || !MessageDigest.isEqual(ident.get().secret().getBytes(StandardCharsets.UTF_8),
                req.senderSecret().getBytes(StandardCharsets.UTF_8))) {
            return xml(HttpStatus.UNAUTHORIZED, Cxml.buildSetupResponse(payload, clock.now(), 401, "Unknown identity or bad shared secret", ""));
        }
        String userId = ident.get().defaultUser();
        String email = req.extrinsics().getOrDefault("UserEmail", "");
        if (!email.isEmpty()) {
            userId = sql.maybe("SELECT id FROM accounts.users WHERE lower(email) = lower(?) AND corporation_id = ?",
                    (rs, i) -> rs.getString(1), email, ident.get().corpId()).orElse(userId);
        }
        String id = randomHex(12);
        sql.exec("INSERT INTO procurement.punchout_sessions (id, identity, user_id, buyer_cookie, return_url) VALUES (?,?,?,?,?)",
                id, req.fromIdentity(), userId, req.buyerCookie(), req.returnUrl());
        return xml(HttpStatus.OK, Cxml.buildSetupResponse(payload, clock.now(), 200, "OK", publicUrl + "/punchout/" + id));
    }

    public record PunchoutSession(String id, String identity, String erp, String userId, String userName, String corporation,
                                  String buyerCookie, String returnUrl, String status, String orderMessage, Instant createdAt) {}

    private PunchoutSession loadSession(String id, boolean lock) {
        String q = """
                SELECT s.id, s.identity, i.erp_name, s.user_id, u.name, c.name, s.buyer_cookie, s.return_url, s.status, s.order_message, s.created_at
                FROM procurement.punchout_sessions s
                JOIN procurement.punchout_identities i ON i.identity = s.identity
                JOIN accounts.users u ON u.id = s.user_id
                JOIN accounts.org_units c ON c.id = u.corporation_id WHERE s.id = ?""";
        if (lock) {
            q += " FOR UPDATE OF s";
        }
        return sql.maybe(q, (rs, i) -> new PunchoutSession(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                        Sql.instant(rs, 11)), id)
                .orElseThrow(() -> ApiException.notFound("no PunchOut session " + id));
    }

    @GetMapping("/punchout/sessions/{id}")
    public PunchoutSession session(@PathVariable("id") String id) {
        return loadSession(id, false);
    }

    public static final class CartBody {
        public List<Checkout.LineReq> lines = new ArrayList<>();
    }

    private record CostCenterCodes(String ccCode, String facCode, String facName) {}

    /** Transfers the cart back to the ERP instead of checking out. */
    @PostMapping("/punchout/sessions/{id}/return")
    public Map<String, Object> punchoutReturn(@PathVariable("id") String sessionId, HttpServletRequest r) {
        CartBody body = bodies.json(r, CartBody.class, CartBody::new);
        if (body.lines.isEmpty()) {
            throw ApiException.badRequest("cart is empty");
        }
        return tx.execute(status -> {
            PunchoutSession sess = loadSession(sessionId, true);
            if (!sess.status().equals("open")) {
                throw ApiException.conflict("this PunchOut session already returned its cart");
            }
            Accounts.User u = accounts.loadUser(sess.userId());
            Catalog.PriceBook book = catalog.loadPriceBook(u.corporationId());
            List<String> skus = new ArrayList<>();
            for (Checkout.LineReq l : body.lines) {
                skus.add(l == null ? "" : l.sku);
            }
            Map<String, Catalog.Product> prods = catalog.loadProducts(skus);

            OffsetDateTime now = clock.now();
            List<Cxml.Item> items = new ArrayList<>();
            Edi.PurchaseOrder po = new Edi.PurchaseOrder();
            po.poNumber = "PO-" + (4500000000L + now.toEpochSecond() % 100000000);
            double total = 0;
            for (int i = 0; i < body.lines.size(); i++) {
                Checkout.LineReq l = body.lines.get(i) == null ? new Checkout.LineReq() : body.lines.get(i);
                Catalog.Product p = prods.get(l.sku);
                if (p == null || l.qty <= 0) {
                    throw ApiException.badRequest("invalid cart line " + l.sku);
                }
                var price = book.quote(p, l.qty);
                total += price.extended();
                items.add(new Cxml.Item(p.sku(), l.qty, price.unitPrice(), p.name(), p.uom(),
                        book.cats().get(p.categoryId()).unspsc(), p.mfrPartNumber(), p.brand()));
                po.lines.add(new Edi.POLine(String.valueOf(i + 1), l.qty, p.uom(), price.unitPrice(), p.sku(), "", p.name()));
            }
            String msg = Cxml.buildOrderMessage(clock.now().toEpochSecond() + "." + randomHex(4) + "@mromirror", clock.now(),
                    sess.identity(), sess.buyerCookie(), items);
            sql.exec("UPDATE procurement.punchout_sessions SET status = 'returned', order_message = ? WHERE id = ?", msg, sess.id());

            // After the ERP approves the requisition it transmits a real purchase order.
            // Pre-build that EDI 850 so the round trip can be completed in the simulator.
            String isa = sql.maybe("SELECT isa_id FROM procurement.edi_partners WHERE corporation_id = ? ORDER BY isa_id LIMIT 1",
                    (rs, i) -> rs.getString(1), u.corporationId()).orElse("");
            CostCenterCodes codes = sql.maybe("""
                    SELECT cc.code, f.code, f.name FROM accounts.org_units cc JOIN accounts.org_units f ON f.id = cc.parent_id
                    WHERE cc.id = ?""", (rs, i) -> new CostCenterCodes(rs.getString(1), rs.getString(2), rs.getString(3)),
                    u.costCenterId()).orElse(new CostCenterCodes("", "", ""));
            po.department = codes.ccCode();
            po.shipToCode = codes.facCode();
            po.shipToName = codes.facName();
            String edi850 = "";
            if (!isa.isEmpty()) {
                OffsetDateTime t = clock.now();
                edi850 = Edi.build850(new Edi.Interchange(isa, OUR_ISA, (int) (t.toEpochSecond() % 1_000_000_000), t), po);
            }
            return obj("session", sess.id(), "erp", sess.erp(), "returnUrl", sess.returnUrl(), "orderMessage", msg,
                    "total", Go.round(total * 100) / 100, "edi850", edi850);
        });
    }

    // ---------------------------------------------------------------- EDI

    public static final class EdiLineResult {
        public String lineNo = "";
        public String poPart = "";
        public String sku = "";
        public int qty;
        public double poPrice;
        public double ourPrice;
        public String status = "";
        public String note = "";
    }

    private record Partner(String corpId, String name, String defaultUser) {}

    private record Resolved(Catalog.Product product, String note) {}

    @PostMapping("/edi/850")
    public Map<String, Object> edi850(HttpServletRequest r) {
        String body = bodies.text(r);
        Edi.PurchaseOrder po;
        try {
            po = Edi.parse850(body);
        } catch (Edi.InvalidEdiException e) {
            throw ApiException.badRequest("850 rejected: " + e.getMessage());
        }
        String senderId = po.senderId;
        Partner partner = sql.maybe("SELECT corporation_id, name, default_user_id FROM procurement.edi_partners WHERE isa_id = ?",
                        (rs, i) -> new Partner(rs.getString(1), rs.getString(2), rs.getString(3)), senderId)
                .orElseThrow(() -> ApiException.badRequest("850 rejected: unknown trading partner " + senderId));
        Accounts.User u = accounts.loadUser(partner.defaultUser());

        // Map the N1 ship-to code to a facility and REF*DP to one of its cost centers.
        List<Accounts.OrgUnit> units = accounts.loadOrgUnits(partner.corpId());
        String costCenter = u.costCenterId();
        for (Accounts.OrgUnit f : units) {
            if (!f.kind.equals("facility") || !f.code.equals(po.shipToCode)) {
                continue;
            }
            String first = "";
            boolean matched = false;
            for (Accounts.OrgUnit cc : units) {
                if (!cc.kind.equals("cost_center") || cc.parentId == null || !cc.parentId.equals(f.id)) {
                    continue;
                }
                if (first.isEmpty()) {
                    first = cc.id;
                }
                if (cc.code.equals(po.department)) {
                    costCenter = cc.id;
                    matched = true;
                }
            }
            if (!matched && !first.isEmpty()) {
                costCenter = first;
            }
        }

        Catalog.PriceBook book = catalog.loadPriceBook(partner.corpId());
        List<EdiLineResult> results = new ArrayList<>();
        List<Checkout.LineReq> accepted = new ArrayList<>();
        for (Edi.POLine l : po.lines) {
            EdiLineResult res = new EdiLineResult();
            res.lineNo = l.lineNo;
            res.poPart = Go.firstNonEmpty(l.vendorPart, l.mfrPart);
            res.qty = l.qty;
            res.poPrice = l.unitPrice;
            Resolved found = resolveEdiPart(l);
            res.note = found.note();
            Catalog.Product p = found.product();
            if (p == null) {
                res.status = "IR";
                res.note = "unknown item";
                results.add(res);
                continue;
            }
            res.sku = p.sku();
            res.ourPrice = book.quote(p, l.qty).unitPrice();
            res.status = "IA";
            if (Math.abs(res.ourPrice - l.unitPrice) > 0.005) {
                res.status = "IP";
                res.note = Go.trimSpace(res.note + " PO price $" + Go.fixed(l.unitPrice, 2) + " differs from contract price $"
                        + Go.fixed(res.ourPrice, 2));
            }
            results.add(res);
            accepted.add(new Checkout.LineReq(p.sku(), l.qty, ""));
        }

        String orderId = "";
        Checkout.Evaluation ev = null;
        String outcome = "rejected";
        if (!accepted.isEmpty()) {
            Checkout.OrderReq req = new Checkout.OrderReq();
            req.lines = accepted;
            req.costCenterId = costCenter;
            req.poNumber = po.poNumber;
            req.source = "edi";
            try {
                Checkout.Placed placed = checkout.placeOrder(u, req);
                orderId = placed.orderId();
                ev = placed.evaluation();
                outcome = "accepted";
            } catch (Checkout.BlockedException e) {
                ev = e.evaluation();
                for (EdiLineResult res : results) {
                    if (!res.status.equals("IR")) {
                        res.status = "IR";
                        res.note = "rejected by purchasing governance";
                    }
                }
            }
        }
        if (ev != null && outcome.equals("accepted")) {
            Set<String> back = new HashSet<>();
            for (Sourcing.Allocation b : ev.plan.backorders) {
                back.add(b.sku());
            }
            for (EdiLineResult res : results) {
                if (res.status.equals("IA") && back.contains(res.sku)) {
                    res.status = "IB";
                    res.note = "accepted; part of the quantity is backordered";
                }
            }
        }

        List<Edi.AckLine> ack = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            EdiLineResult res = results.get(i);
            String uom = po.lines.get(i).uom.isEmpty() ? "EA" : po.lines.get(i).uom;
            ack.add(new Edi.AckLine(res.lineNo, res.qty, uom, res.status.equals("IR") ? res.poPrice : res.ourPrice,
                    Go.firstNonEmpty(res.sku, res.poPart), res.status));
        }
        OffsetDateTime now = clock.now();
        String doc855 = Edi.build855(new Edi.Interchange(OUR_ISA, senderId, now.getNano(), now), po.poNumber, ack);

        String oid = orderId.isEmpty() ? null : orderId;
        sql.exec("""
                INSERT INTO procurement.edi_messages (direction, doc_type, partner, order_id, body) VALUES
                ('inbound', '850', ?, ?, ?), ('outbound', '855', ?, ?, ?)""",
                senderId, oid, body, senderId, oid, doc855);
        Map<String, Object> resp = obj("po", po, "partner", partner.name(), "orderId", orderId, "outcome", outcome,
                "lines", results, "ack855", doc855);
        if (ev != null) {
            resp.put("governance", ev.governance);
            resp.put("plan", ev.plan);
        }
        return resp;
    }

    /**
     * Finds the SKU a PO1 line means: our part number, the replacement of a
     * discontinued one, or a cross-referenced competitor/OEM number.
     */
    private Resolved resolveEdiPart(Edi.POLine l) {
        if (!l.vendorPart.isEmpty()) {
            String sku = l.vendorPart.toUpperCase(Locale.ROOT);
            Catalog.Product p = catalog.loadProduct(sku);
            if (p != null) {
                if (p.status().equals("active")) {
                    return new Resolved(p, "");
                }
                if (p.replacementSku() != null) {
                    return new Resolved(catalog.loadProduct(p.replacementSku()),
                            p.sku() + " discontinued; substituted " + p.replacementSku());
                }
            }
        }
        for (String part : new String[]{l.vendorPart, l.mfrPart}) {
            if (part.isEmpty()) {
                continue;
            }
            List<Catalog.CrossRefHit> hits;
            try {
                hits = catalog.resolveCrossRefs(part);
            } catch (DataAccessException e) {
                continue;
            }
            if (hits.isEmpty()) {
                continue;
            }
            Catalog.CrossRefHit hit = hits.get(0);
            return new Resolved(catalog.loadProduct(hit.sku()), "cross-referenced " + hit.refBrand() + " " + hit.refPartNumber());
        }
        return new Resolved(null, "");
    }

    public record EdiMessage(int id, String direction, String docType, String partner, String orderId, String body,
                             Instant createdAt) {}

    @GetMapping("/edi/messages")
    public List<EdiMessage> ediMessages() {
        return sql.list("SELECT id, direction, doc_type, partner, order_id, body, created_at FROM procurement.edi_messages ORDER BY id DESC LIMIT 50",
                (rs, i) -> new EdiMessage(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), Sql.instant(rs, 7)));
    }
}
