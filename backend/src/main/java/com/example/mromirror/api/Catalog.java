package com.example.mromirror.api;

import com.example.mromirror.governance.Governance;
import com.example.mromirror.pricing.Pricing;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Reads the PIM and Pricing contexts: categories, products, cross references and price books. */
@Component
public class Catalog {

    public record AttrDef(String key, String label, String type,
                          @JsonInclude(JsonInclude.Include.NON_EMPTY) String unit, boolean facet) {}

    public record Category(String id, String parentId, String name, String unspsc, List<AttrDef> attributeSchema) {}

    /** Every category by ID, in name order, with tree walks over parent_id. */
    public static final class Categories {
        private final Map<String, Category> byId = new LinkedHashMap<>();

        public Category get(String id) {
            return byId.get(id);
        }

        public Collection<Category> all() {
            return byId.values();
        }

        /** id followed by each ancestor up to the root. */
        public List<String> lineage(String id) {
            List<String> out = new ArrayList<>();
            for (Category c = byId.get(id); c != null; ) {
                out.add(c.id());
                if (c.parentId() == null) {
                    break;
                }
                c = byId.get(c.parentId());
            }
            return out;
        }

        public List<String> descendants(String id) {
            List<String> out = new ArrayList<>();
            out.add(id);
            for (Category c : byId.values()) {
                if (!c.id().equals(id) && lineage(c.id()).contains(id)) {
                    out.add(c.id());
                }
            }
            return out;
        }

        public List<Map<String, String>> breadcrumb(String id) {
            List<String> ids = lineage(id);
            List<Map<String, String>> out = new ArrayList<>();
            for (int i = ids.size() - 1; i >= 0; i--) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", ids.get(i));
                m.put("name", byId.get(ids.get(i)).name());
                out.add(m);
            }
            return out;
        }
    }

    public record Product(String sku, String categoryId, String name, String brand, String mfrPartNumber,
                          String description, double listPrice, String uom, double weightLb,
                          Map<String, Object> attributes, String hazmatUn, String hazmatClass, String sdsUrl,
                          boolean dropShipOnly, String status, String replacementSku) {
        @JsonIgnore
        public boolean hazmat() {
            return hazmatUn != null;
        }
    }

    static final String PRODUCT_COLS = """
            sku, category_id, name, brand, mfr_part_number, description, list_price, uom, weight_lb,
            attributes, hazmat_un, hazmat_class, sds_url, drop_ship_only, status, replacement_sku""";

    public record CrossRefHit(String refType, String refBrand, String refPartNumber, String note, String sku,
                              String name, String brand) {}

    /** One corporation's commercial agreements, so many lines can be priced in memory. */
    public static final class PriceBook {
        final Categories cats;
        final Map<String, Pricing.Contract> contracts = new HashMap<>();
        final Map<String, Double> discounts = new HashMap<>();
        final Map<String, List<Pricing.Tier>> tiers = new HashMap<>();
        final Map<String, String> restrictions = new HashMap<>();
        final LocalDate today;

        PriceBook(Categories cats, LocalDate today) {
            this.cats = cats;
            this.today = today;
        }

        public Categories cats() {
            return cats;
        }

        /** The volume breaks of the most specific category that defines any, or null. */
        public List<Pricing.Tier> tiersFor(String catId) {
            for (String id : cats.lineage(catId)) {
                List<Pricing.Tier> t = tiers.get(id);
                if (t != null) {
                    return t;
                }
            }
            return null;
        }

        public Pricing.Result quote(Product p, int qty) {
            Pricing.CategoryDiscount disc = null;
            for (String id : cats.lineage(p.categoryId())) {
                Double pct = discounts.get(id);
                if (pct != null) {
                    disc = new Pricing.CategoryDiscount(id, cats.get(id).name(), pct);
                    break;
                }
            }
            return Pricing.resolve(new Pricing.Input(p.listPrice(), qty, contracts.get(p.sku()), disc,
                    tiersFor(p.categoryId()), today));
        }

        public Governance.Restriction restriction(String catId) {
            for (String id : cats.lineage(catId)) {
                String cert = restrictions.get(id);
                if (cert != null) {
                    return new Governance.Restriction(cats.get(id).name(), cert);
                }
            }
            return null;
        }
    }

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]");
    private static final TypeReference<List<AttrDef>> ATTR_DEFS = new TypeReference<>() {};
    private static final TypeReference<TreeMap<String, Object>> ATTRIBUTES = new TypeReference<>() {};

    private final Sql sql;
    private final ObjectMapper mapper;
    private final Clock clock;

    public Catalog(Sql sql, ObjectMapper mapper, Clock clock) {
        this.sql = sql;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Makes "6205-2RS1", "6205 2rs1" and "62052RS1" compare equal. */
    static String normalizePart(String s) {
        return NON_ALNUM.matcher(s).replaceAll("").toUpperCase(Locale.ROOT);
    }

    /** An attribute value as a facet string: 1.5, 20, true, TEFC. */
    static String attrString(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Double || v instanceof Float) {
            return BigDecimal.valueOf(((Number) v).doubleValue()).stripTrailingZeros().toPlainString();
        }
        if (v instanceof BigDecimal d) {
            return BigDecimal.valueOf(d.doubleValue()).stripTrailingZeros().toPlainString();
        }
        return v.toString();
    }

    String toJson(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Categories loadCategories() {
        Categories cs = new Categories();
        for (Category c : sql.list("SELECT id, parent_id, name, coalesce(unspsc, ''), attribute_schema FROM pim.categories ORDER BY name",
                (rs, i) -> new Category(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        fromJson(rs.getString(5), ATTR_DEFS)))) {
            cs.byId.put(c.id(), c);
        }
        return cs;
    }

    Product mapProduct(ResultSet rs, int row) throws SQLException {
        return new Product(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getDouble(7), rs.getString(8), rs.getDouble(9), fromJson(rs.getString(10), ATTRIBUTES),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getBoolean(14), rs.getString(15), rs.getString(16));
    }

    public List<Product> queryProducts(String where, Object... args) {
        return sql.list("SELECT " + PRODUCT_COLS + " FROM pim.products " + where, this::mapProduct, args);
    }

    public Map<String, Product> loadProducts(Collection<String> skus) {
        Map<String, Product> m = new HashMap<>();
        for (Product p : queryProducts("WHERE sku = ANY(?)", Sql.textArray(skus))) {
            m.put(p.sku(), p);
        }
        return m;
    }

    public Product loadProduct(String sku) {
        return loadProducts(List.of(sku)).get(sku);
    }

    public PriceBook loadPriceBook(String corpId) {
        PriceBook b = new PriceBook(loadCategories(), clock.today());
        sql.list("SELECT sku, price, contract_ref, valid_to FROM pricing.contract_prices WHERE corporation_id = ?",
                (rs, i) -> b.contracts.put(rs.getString(1), new Pricing.Contract(rs.getDouble(2), rs.getString(3), Sql.date(rs, 4))),
                corpId);
        sql.list("SELECT category_id, discount_pct FROM pricing.category_discounts WHERE corporation_id = ?",
                (rs, i) -> b.discounts.put(rs.getString(1), rs.getDouble(2)), corpId);
        for (Pricing.Tier t : sql.list("SELECT category_id, min_qty, discount_pct FROM pricing.volume_tiers ORDER BY min_qty",
                (rs, i) -> new Pricing.Tier(rs.getString(1), rs.getInt(2), rs.getDouble(3)))) {
            b.tiers.computeIfAbsent(t.categoryId(), k -> new ArrayList<>()).add(t);
        }
        sql.list("SELECT category_id, required_cert FROM accounts.category_restrictions WHERE corporation_id = ?",
                (rs, i) -> b.restrictions.put(rs.getString(1), rs.getString(2)), corpId);
        return b;
    }

    /** Maps a competitor, OEM, obsolete or UPC number to our SKU. */
    public List<CrossRefHit> resolveCrossRefs(String text) {
        String norm = normalizePart(text);
        if (norm.length() < 3) {
            return List.of();
        }
        return sql.list("""
                SELECT x.ref_type, x.ref_brand, x.ref_part_number, x.note, p.sku, p.name, p.brand
                FROM pim.cross_references x JOIN pim.products p ON p.sku = x.sku
                WHERE x.ref_norm = ?
                UNION ALL
                SELECT 'mfr', p.brand, p.mfr_part_number, 'Manufacturer part number', p.sku, p.name, p.brand
                FROM pim.products p
                WHERE upper(regexp_replace(p.mfr_part_number, '[^A-Za-z0-9]', '', 'g')) = ? AND p.status = 'active'""",
                (rs, i) -> new CrossRefHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)),
                norm, norm);
    }
}
