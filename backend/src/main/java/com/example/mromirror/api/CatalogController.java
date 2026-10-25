package com.example.mromirror.api;

import com.example.mromirror.pricing.Pricing;
import com.example.mromirror.sourcing.Sourcing;
import com.example.mromirror.support.Go;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.example.mromirror.api.Json.obj;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final Sql sql;
    private final Catalog catalog;
    private final Inventory inventory;

    public CatalogController(Sql sql, Catalog catalog, Inventory inventory) {
        this.sql = sql;
        this.catalog = catalog;
        this.inventory = inventory;
    }

    private static String param(HttpServletRequest r, String name) {
        String v = r.getParameter(name);
        return v == null ? "" : v;
    }

    // ---------------------------------------------------------------- categories

    record CategoryNode(String id, String parentId, String name, String unspsc, List<Catalog.AttrDef> attributeSchema,
                        int count, List<CategoryNode> children) {}

    @GetMapping("/categories")
    public List<CategoryNode> categories() {
        Catalog.Categories cs = catalog.loadCategories();
        Map<String, Integer> counts = new HashMap<>();
        sql.list("SELECT category_id, count(*) FROM pim.products WHERE status = 'active' GROUP BY category_id", (rs, i) -> {
            for (String id : cs.lineage(rs.getString(1))) {
                counts.merge(id, rs.getInt(2), Integer::sum);
            }
            return null;
        });
        Map<String, CategoryNode> nodes = new LinkedHashMap<>();
        for (Catalog.Category c : cs.all()) {
            nodes.put(c.id(), new CategoryNode(c.id(), c.parentId(), c.name(), c.unspsc(), c.attributeSchema(),
                    counts.getOrDefault(c.id(), 0), new ArrayList<>()));
        }
        List<CategoryNode> roots = new ArrayList<>();
        for (CategoryNode n : nodes.values()) {
            if (n.parentId() == null) {
                roots.add(n);
            } else {
                nodes.get(n.parentId()).children().add(n);
            }
        }
        Comparator<CategoryNode> byName = Comparator.comparing(CategoryNode::name);
        roots.sort(byName);
        for (CategoryNode n : nodes.values()) {
            n.children().sort(byName);
        }
        return roots;
    }

    // ---------------------------------------------------------------- search

    record SearchItem(@JsonUnwrapped Catalog.Product product, String categoryName, Pricing.Result price,
                      Inventory.Availability availability) {}

    record FacetValue(String value, int count, boolean selected) {}

    record Facet(@JsonUnwrapped Catalog.AttrDef def, List<FacetValue> values) {}

    record CategoryFacet(String id, String name, int count) {}

    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    private static Double parseNumber(String s) {
        return DECIMAL.matcher(s).matches() ? Double.parseDouble(s) : null;
    }

    /** Numeric values sort by value, anything else alphabetically. */
    private static boolean facetLess(FacetValue x, FacetValue y) {
        Double a = parseNumber(x.value());
        Double b = parseNumber(y.value());
        if (a != null && b != null) {
            return a < b;
        }
        return x.value().compareTo(y.value()) < 0;
    }

    @GetMapping("/products")
    public Map<String, Object> search(Accounts.User u, HttpServletRequest r) {
        String text = Go.trimSpace(param(r, "q"));
        String catId = param(r, "category");

        Catalog.PriceBook book = catalog.loadPriceBook(u.corporationId());
        Catalog.Categories cats = book.cats();
        if (!catId.isEmpty() && cats.get(catId) == null) {
            throw ApiException.badRequest("unknown category " + catId);
        }

        StringBuilder where = new StringBuilder("WHERE status = 'active'");
        List<Object> args = new ArrayList<>();
        if (!catId.isEmpty()) {
            args.add(Sql.textArray(cats.descendants(catId)));
            where.append(" AND category_id = ANY(?)");
        }
        for (String tok : Go.fields(text)) {
            args.add(tok);
            where.append(" AND (sku || ' ' || name || ' ' || brand || ' ' || mfr_part_number || ' ' || description) ILIKE '%' || ? || '%'");
        }
        where.append(" ORDER BY category_id, list_price");
        List<Catalog.Product> matched = catalog.queryProducts(where.toString(), args.toArray());

        List<Catalog.CrossRefHit> crossRefs = catalog.resolveCrossRefs(text);

        // Category facet counts come from the text match alone.
        Map<String, Integer> catCounts = new LinkedHashMap<>();
        for (Catalog.Product p : matched) {
            catCounts.merge(p.categoryId(), 1, Integer::sum);
        }
        List<CategoryFacet> categoryFacets = new ArrayList<>();
        catCounts.forEach((id, n) -> categoryFacets.add(new CategoryFacet(id, cats.get(id).name(), n)));
        categoryFacets.sort(Comparator.comparingInt(CategoryFacet::count).reversed());

        // Technical facets are only meaningful inside one category's schema.
        List<Catalog.AttrDef> defs = new ArrayList<>();
        if (!catId.isEmpty()) {
            Set<String> seen = new HashSet<>();
            List<String> ids = new ArrayList<>();
            ids.add(catId);
            ids.addAll(cats.descendants(catId));
            for (String id : ids) {
                for (Catalog.AttrDef d : cats.get(id).attributeSchema()) {
                    if (d.facet() && seen.add(d.key())) {
                        defs.add(d);
                    }
                }
            }
        }
        Map<String, List<String>> selected = new LinkedHashMap<>();
        r.getParameterMap().forEach((k, v) -> {
            if (k.startsWith("f.") && v.length > 0) {
                selected.put(k.substring(2), Arrays.asList(v));
            }
        });

        // Disjunctive faceting: each facet counts products that pass every *other* filter.
        List<Facet> facetList = new ArrayList<>();
        for (Catalog.AttrDef d : defs) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Catalog.Product p : matched) {
                if (p.attributes().containsKey(d.key()) && matches(p, selected, d.key())) {
                    counts.merge(Catalog.attrString(p.attributes().get(d.key())), 1, Integer::sum);
                }
            }
            if (counts.isEmpty()) {
                continue;
            }
            List<FacetValue> values = new ArrayList<>();
            counts.forEach((v, n) -> values.add(new FacetValue(v, n,
                    selected.getOrDefault(d.key(), List.of()).contains(v))));
            insertionSort(values);
            facetList.add(new Facet(d, values));
        }

        List<Catalog.Product> results = matched.stream().filter(p -> matches(p, selected, "")).toList();
        List<String> skus = results.stream().map(Catalog.Product::sku).toList();
        Map<String, Map<String, Integer>> atp = inventory.loadATP(skus, false);
        List<Sourcing.Node> nodes = inventory.loadNodes();
        Inventory.Facility fac;
        try {
            fac = inventory.loadFacility(u.facilityId());
        } catch (DataAccessException e) {
            fac = Inventory.Facility.ZERO;
        }

        List<SearchItem> items = new ArrayList<>();
        for (Catalog.Product p : results) {
            items.add(new SearchItem(p, cats.get(p.categoryId()).name(), book.quote(p, 1),
                    Inventory.availabilityFor(atp, nodes, fac, p.sku())));
        }
        return obj("query", text, "category", catId, "total", items.size(), "items", items,
                "facets", facetList, "categoryFacets", categoryFacets, "crossRefs", crossRefs);
    }

    private static boolean matches(Catalog.Product p, Map<String, List<String>> selected, String skip) {
        for (Map.Entry<String, List<String>> e : selected.entrySet()) {
            if (!e.getKey().equals(skip) && !e.getValue().contains(Catalog.attrString(p.attributes().get(e.getKey())))) {
                return false;
            }
        }
        return true;
    }

    /** facetLess is not a total order over mixed values, so avoid TimSort's contract check. */
    private static void insertionSort(List<FacetValue> v) {
        for (int i = 1; i < v.size(); i++) {
            for (int j = i; j > 0 && facetLess(v.get(j), v.get(j - 1)); j--) {
                FacetValue t = v.get(j);
                v.set(j, v.get(j - 1));
                v.set(j - 1, t);
            }
        }
    }

    // ---------------------------------------------------------------- product detail

    record Alt(@JsonUnwrapped Catalog.Product product, Pricing.Result price) {}

    record NodeStock(@JsonUnwrapped Sourcing.Node node, int atp, double distanceMi, boolean stocked) {}

    record CorpPrice(String corporationId, String corporation, Pricing.Result price) {}

    @GetMapping("/products/{sku}")
    public Map<String, Object> product(Accounts.User u, @PathVariable("sku") String skuParam, HttpServletRequest r) {
        String sku = skuParam.toUpperCase(Locale.ROOT);
        int qty = Math.max(Go.atoiOrZero(param(r, "qty")), 1);

        Catalog.Product p = catalog.loadProduct(sku);
        if (p == null) {
            throw ApiException.notFound("no product " + sku);
        }
        Catalog.PriceBook book = catalog.loadPriceBook(u.corporationId());
        Catalog.Category cat = book.cats().get(p.categoryId());

        var xrefs = sql.list("SELECT ref_type, ref_brand, ref_part_number, note FROM pim.cross_references WHERE sku = ? ORDER BY ref_type, ref_brand",
                (rs, i) -> new Catalog.CrossRefHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), "", "", ""),
                sku);

        List<Alt> alts = new ArrayList<>();
        for (Catalog.Product e : catalog.queryProducts("WHERE category_id = ? AND sku <> ? AND status = 'active' AND attributes = ?::jsonb",
                p.categoryId(), sku, catalog.toJson(p.attributes()))) {
            alts.add(new Alt(e, book.quote(e, qty)));
        }
        Alt replacement = null;
        if (p.replacementSku() != null) {
            Catalog.Product x = catalog.loadProduct(p.replacementSku());
            if (x != null) {
                replacement = new Alt(x, book.quote(x, qty));
            }
        }

        // Inventory by node, nearest first.
        Map<String, Map<String, Integer>> atp = inventory.loadATP(List.of(sku), false);
        List<Sourcing.Node> nodes = inventory.loadNodes();
        Inventory.Facility fac = inventory.loadFacility(u.facilityId());
        List<NodeStock> stock = new ArrayList<>();
        for (Sourcing.Node n : nodes) {
            Map<String, Integer> m = atp.get(n.id());
            boolean ok = m != null && m.containsKey(sku);
            stock.add(new NodeStock(n, Math.max(ok ? m.get(sku) : 0, 0),
                    Go.round(Sourcing.distanceMi(fac.lat(), fac.lon(), n.lat(), n.lon())), ok));
        }
        stock.sort(Comparator.comparingDouble(NodeStock::distanceMi));

        // The same SKU through every corporation's price book: the heart of contract pricing.
        List<CorpPrice> corps = new ArrayList<>();
        for (String[] c : sql.list("SELECT id, name FROM accounts.org_units WHERE kind = 'corporation' ORDER BY name",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)})) {
            corps.add(new CorpPrice(c[0], c[1], catalog.loadPriceBook(c[0]).quote(p, qty)));
        }

        Map<String, String> facility = new LinkedHashMap<>();
        facility.put("id", fac.id());
        facility.put("name", fac.name());
        return obj("product", p, "category", cat, "breadcrumb", book.cats().breadcrumb(p.categoryId()),
                "price", book.quote(p, qty), "tiers", book.tiersFor(p.categoryId()), "restriction", book.restriction(p.categoryId()),
                "crossRefs", xrefs, "equivalents", alts, "replacement", replacement, "inventory", stock,
                "priceByCustomer", corps, "facility", facility);
    }

    // ---------------------------------------------------------------- network

    record Summary(String node, String city, String state, int skus, int units, int reserved) {}

    record NodeOut(@JsonUnwrapped Sourcing.Node node, String city, String state, int skus, int units, int reserved,
                   double distanceMi) {}

    @GetMapping("/network")
    public Map<String, Object> network(Accounts.User u) {
        List<Sourcing.Node> nodes = inventory.loadNodes();
        Inventory.Facility fac = inventory.loadFacility(u.facilityId());
        Map<String, Summary> byId = new HashMap<>();
        for (Summary x : sql.list("""
                SELECT n.id, n.city, n.state, count(i.sku) FILTER (WHERE i.on_hand > 0), coalesce(sum(i.on_hand), 0), coalesce(sum(i.reserved), 0)
                FROM oms.nodes n LEFT JOIN oms.inventory i ON i.node_id = n.id GROUP BY n.id""",
                (rs, i) -> new Summary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)))) {
            byId.put(x.node(), x);
        }
        List<NodeOut> out = new ArrayList<>();
        for (Sourcing.Node n : nodes) {
            Summary x = byId.getOrDefault(n.id(), new Summary("", "", "", 0, 0, 0));
            out.add(new NodeOut(n, x.city(), x.state(), x.skus(), x.units(), x.reserved(),
                    Go.round(Sourcing.distanceMi(fac.lat(), fac.lon(), n.lat(), n.lon()))));
        }
        out.sort(Comparator.comparingDouble(NodeOut::distanceMi));
        return obj("facility", obj("id", fac.id(), "name", fac.name(), "state", fac.state(), "lat", fac.lat(), "lon", fac.lon()),
                "nodes", out, "courierRadiusMi", Sourcing.COURIER_RADIUS_MI);
    }
}
