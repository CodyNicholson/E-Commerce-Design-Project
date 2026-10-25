package com.example.mromirror.api;

import com.example.mromirror.governance.Governance;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Reads the Accounts context: users, the org hierarchy, approval chains and blanket POs. */
@Component
public class Accounts {

    public record User(String id, String name, String email, String title, String role, String corporationId,
                       String corporation, String facilityId, String costCenterId, double perOrderLimit,
                       double monthlyLimit, String approverId, List<String> certifications, String badgeId) {}

    static final String USER_COLS = """
            u.id, u.name, u.email, u.title, u.role, u.corporation_id, c.name, coalesce(u.facility_id, ''),
            coalesce(u.cost_center_id, ''), u.per_order_limit, u.monthly_limit, u.approver_id, u.certifications, u.badge_id""";

    static final String USER_FROM = " FROM accounts.users u JOIN accounts.org_units c ON c.id = u.corporation_id";

    static User mapUser(ResultSet rs, int row) throws SQLException {
        return new User(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getDouble(10), rs.getDouble(11),
                rs.getString(12), Sql.strings(rs, 13), rs.getString(14));
    }

    public static final class OrgUnit {
        public String id;
        public String parentId;
        public String kind;
        public String name;
        public String code;
        public String state;
        public String city;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Double lat;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Double lon;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<OrgUnit> children = new ArrayList<>();
    }

    public record BlanketPo(String id, String facilityId, String poNumber, double authorized, double consumed,
                            LocalDate validFrom, LocalDate validTo) {}

    private final Sql sql;

    public Accounts(Sql sql) {
        this.sql = sql;
    }

    /** Throws EmptyResultDataAccessException for an unknown user. */
    public User loadUser(String id) {
        return sql.one("SELECT " + USER_COLS + USER_FROM + " WHERE u.id = ?", Accounts::mapUser, id);
    }

    /** Walks approver_id upward: direct approver first. */
    public List<Governance.Approver> approverChain(String userId) {
        return sql.list("""
                WITH RECURSIVE chain AS (
                    SELECT a.id, a.name, a.title, a.per_order_limit, a.approver_id, 1 AS depth
                    FROM accounts.users u JOIN accounts.users a ON a.id = u.approver_id WHERE u.id = ?
                    UNION ALL
                    SELECT a.id, a.name, a.title, a.per_order_limit, a.approver_id, c.depth + 1
                    FROM chain c JOIN accounts.users a ON a.id = c.approver_id WHERE c.depth < 10
                )
                SELECT id, name, title, per_order_limit FROM chain ORDER BY depth""",
                (rs, i) -> new Governance.Approver(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4)),
                userId);
    }

    /** The buyer's month-to-date committed spend (everything not rejected). */
    public double mtdSpend(String userId) {
        return sql.one("""
                SELECT coalesce(sum(total), 0)::float8 FROM oms.orders
                WHERE user_id = ? AND status <> 'rejected' AND created_at >= date_trunc('month', now())""",
                (rs, i) -> rs.getDouble(1), userId);
    }

    public List<OrgUnit> loadOrgUnits(String corpId) {
        return sql.list("""
                WITH RECURSIVE tree AS (
                    SELECT * FROM accounts.org_units WHERE id = ?
                    UNION ALL
                    SELECT o.* FROM accounts.org_units o JOIN tree t ON o.parent_id = t.id
                )
                SELECT id, parent_id, kind, name, code, state, city, lat, lon FROM tree ORDER BY code""",
                (rs, i) -> {
                    OrgUnit o = new OrgUnit();
                    o.id = rs.getString(1);
                    o.parentId = rs.getString(2);
                    o.kind = rs.getString(3);
                    o.name = rs.getString(4);
                    o.code = rs.getString(5);
                    o.state = rs.getString(6);
                    o.city = rs.getString(7);
                    o.lat = Sql.nullableDouble(rs, 8);
                    o.lon = Sql.nullableDouble(rs, 9);
                    return o;
                }, corpId);
    }

    public List<BlanketPo> loadBlanketPOs(String corpId) {
        return sql.list("""
                SELECT id, facility_id, po_number, amount_authorized, amount_consumed, valid_from, valid_to
                FROM accounts.blanket_pos WHERE corporation_id = ? ORDER BY po_number""",
                (rs, i) -> new BlanketPo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                        rs.getDouble(5), Sql.date(rs, 6), Sql.date(rs, 7)),
                corpId);
    }
}
