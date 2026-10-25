package com.example.mromirror.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.mromirror.api.Json.obj;

@RestController
@RequestMapping("/api")
public class AccountsController {
    private final Sql sql;
    private final Accounts accounts;
    private final Clock clock;

    public AccountsController(Sql sql, Accounts accounts, Clock clock) {
        this.sql = sql;
        this.accounts = accounts;
        this.clock = clock;
    }

    @GetMapping("/users")
    public List<Accounts.User> users() {
        return sql.list("SELECT " + Accounts.USER_COLS + Accounts.USER_FROM + " ORDER BY c.name, u.per_order_limit", Accounts::mapUser);
    }

    record CostCenterOption(String id, String name, String code, String facilityId, String facility, String state) {}

    @GetMapping("/me")
    public Map<String, Object> me(Accounts.User u) {
        var chain = accounts.approverChain(u.id());
        double mtd = accounts.mtdSpend(u.id());
        List<Accounts.OrgUnit> units = accounts.loadOrgUnits(u.corporationId());
        var bpos = accounts.loadBlanketPOs(u.corporationId());
        int pending = sql.one("SELECT count(*) FROM oms.orders WHERE approver_id = ? AND status = 'pending_approval'",
                (rs, i) -> rs.getInt(1), u.id());

        // Cost centers the buyer may charge: every cost center in the corporation, grouped by facility.
        Map<String, Accounts.OrgUnit> byId = new HashMap<>();
        for (Accounts.OrgUnit o : units) {
            byId.put(o.id, o);
        }
        List<CostCenterOption> ccs = new ArrayList<>();
        for (Accounts.OrgUnit o : units) {
            if (o.kind.equals("cost_center") && o.parentId != null) {
                Accounts.OrgUnit f = byId.get(o.parentId);
                ccs.add(new CostCenterOption(o.id, o.name, o.code, f.id, f.name, f.state == null ? "" : f.state));
            }
        }
        List<String> path = new ArrayList<>();
        for (String id = u.costCenterId(); !id.isEmpty(); ) {
            Accounts.OrgUnit o = byId.get(id);
            if (o == null) {
                break;
            }
            path.add(0, o.name);
            if (o.parentId == null) {
                break;
            }
            id = o.parentId;
        }
        return obj("user", u, "approverChain", chain, "mtdSpend", mtd, "costCenters", ccs, "orgPath", path,
                "blanketPOs", bpos, "pendingApprovals", pending);
    }

    record UserRow(@JsonUnwrapped Accounts.User user, double mtdSpend) {}

    record Exemption(String state, String certificate, String type, LocalDate expiresOn, boolean valid) {}

    record Restriction(String categoryId, String category, String cert) {}

    record Contract(String sku, String name, double listPrice, double price, String ref, LocalDate validTo) {}

    record Discount(String categoryId, String category, double pct) {}

    @GetMapping("/accounts")
    public Map<String, Object> account(Accounts.User u) {
        List<Accounts.OrgUnit> units = accounts.loadOrgUnits(u.corporationId());
        Map<String, Accounts.OrgUnit> byId = new HashMap<>();
        Accounts.OrgUnit root = null;
        for (Accounts.OrgUnit o : units) {
            byId.put(o.id, o);
        }
        for (Accounts.OrgUnit o : units) {
            if (o.parentId == null) {
                root = o;
            } else if (byId.get(o.parentId) != null) {
                byId.get(o.parentId).children.add(o);
            }
        }

        List<UserRow> users = new ArrayList<>();
        for (Accounts.User x : sql.list("SELECT " + Accounts.USER_COLS + Accounts.USER_FROM
                + " WHERE u.corporation_id = ? ORDER BY u.per_order_limit DESC", Accounts::mapUser, u.corporationId())) {
            users.add(new UserRow(x, accounts.mtdSpend(x.id())));
        }

        var bpos = accounts.loadBlanketPOs(u.corporationId());
        LocalDate today = clock.today();
        var exemptions = sql.list("""
                SELECT state, certificate_number, exemption_type, expires_on FROM accounts.tax_exemptions
                WHERE corporation_id = ? ORDER BY state""",
                (rs, i) -> {
                    LocalDate expires = Sql.date(rs, 4);
                    return new Exemption(rs.getString(1), rs.getString(2), rs.getString(3), expires, !today.isAfter(expires));
                }, u.corporationId());
        var restrictions = sql.list("""
                SELECT r.category_id, c.name, r.required_cert FROM accounts.category_restrictions r
                JOIN pim.categories c ON c.id = r.category_id WHERE r.corporation_id = ?""",
                (rs, i) -> new Restriction(rs.getString(1), rs.getString(2), rs.getString(3)), u.corporationId());
        var contracts = sql.list("""
                SELECT cp.sku, p.name, p.list_price, cp.price, cp.contract_ref, cp.valid_to
                FROM pricing.contract_prices cp JOIN pim.products p ON p.sku = cp.sku WHERE cp.corporation_id = ? ORDER BY cp.sku""",
                (rs, i) -> new Contract(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getString(5),
                        Sql.date(rs, 6)), u.corporationId());
        var discounts = sql.list("""
                SELECT d.category_id, c.name, d.discount_pct FROM pricing.category_discounts d
                JOIN pim.categories c ON c.id = d.category_id WHERE d.corporation_id = ? ORDER BY d.discount_pct DESC""",
                (rs, i) -> new Discount(rs.getString(1), rs.getString(2), rs.getDouble(3)), u.corporationId());

        return obj("tree", root, "users", users, "blanketPOs", bpos, "taxExemptions", exemptions,
                "restrictions", restrictions, "contractPrices", contracts, "categoryDiscounts", discounts);
    }
}
