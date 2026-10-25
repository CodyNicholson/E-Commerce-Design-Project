package com.example.mromirror.api;

import com.example.mromirror.sourcing.Sourcing;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads OMS inventory: fulfilment nodes, available-to-promise and ship-to facilities. */
@Component
public class Inventory {

    public record Facility(String id, String name, String code, String state, String corporationId, double lat, double lon) {
        static final Facility ZERO = new Facility("", "", "", "", "", 0, 0);
    }

    public record Availability(int network, int localBranch, String branchName) {}

    private final Sql sql;

    public Inventory(Sql sql) {
        this.sql = sql;
    }

    public List<Sourcing.Node> loadNodes() {
        return sql.list("SELECT id, kind, name, lat, lon, hazmat_capable, will_call, lead_days FROM oms.nodes ORDER BY id",
                (rs, i) -> new Sourcing.Node(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getBoolean(6), rs.getBoolean(7), rs.getInt(8)));
    }

    /**
     * Available-to-promise (on hand minus reserved) per node for the SKUs.
     * forUpdate locks the rows so a concurrent release cannot promise the same units.
     */
    public Map<String, Map<String, Integer>> loadATP(Collection<String> skus, boolean forUpdate) {
        String q = "SELECT node_id, sku, on_hand - reserved FROM oms.inventory WHERE sku = ANY(?)";
        if (forUpdate) {
            q += " ORDER BY node_id, sku FOR UPDATE";
        }
        Map<String, Map<String, Integer>> atp = new HashMap<>();
        sql.list(q, (rs, i) -> atp.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getInt(3)),
                Sql.textArray(skus));
        return atp;
    }

    /** Throws EmptyResultDataAccessException when id is not a facility. */
    public Facility loadFacility(String id) {
        return sql.one("""
                WITH RECURSIVE up AS (
                    SELECT id, parent_id, kind FROM accounts.org_units WHERE id = ?
                    UNION ALL SELECT o.id, o.parent_id, o.kind FROM accounts.org_units o JOIN up ON o.id = up.parent_id
                )
                SELECT f.id, f.name, f.code, coalesce(f.state, ''), coalesce(f.lat, 0), coalesce(f.lon, 0),
                       (SELECT id FROM up WHERE kind = 'corporation')
                FROM accounts.org_units f WHERE f.id = ? AND f.kind = 'facility'""",
                (rs, i) -> new Facility(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(7),
                        rs.getDouble(5), rs.getDouble(6)),
                id, id);
    }

    static int atpOf(Map<String, Map<String, Integer>> atp, String node, String sku) {
        Map<String, Integer> m = atp.get(node);
        return m == null ? 0 : m.getOrDefault(sku, 0);
    }

    /** Summarises stock for search results: whole network and the buyer's nearest branch. */
    static Availability availabilityFor(Map<String, Map<String, Integer>> atp, List<Sourcing.Node> nodes, Facility f, String sku) {
        int network = 0;
        int local = 0;
        String branch = "";
        double best = Double.MAX_VALUE;
        for (Sourcing.Node n : nodes) {
            if (n.kind().equals("supplier")) {
                continue;
            }
            int q = Math.max(atpOf(atp, n.id(), sku), 0);
            network += q;
            if (n.kind().equals("branch") && f != null) {
                double d = Sourcing.distanceMi(f.lat(), f.lon(), n.lat(), n.lon());
                if (d < best) {
                    best = d;
                    local = q;
                    branch = n.name();
                }
            }
        }
        return new Availability(network, local, branch);
    }
}
