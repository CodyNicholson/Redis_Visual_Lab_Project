package com.example.redislab.service;

import com.example.redislab.model.DemoResult;
import com.example.redislab.model.Product;
import com.example.redislab.support.Demo;
import com.example.redislab.support.RedisModules;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisCommandExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Features that ship with Redis 8: JSON documents, the Query Engine, time series and vector sets. */
@Service
public class ModulesService {

    public static final String SEARCH_INDEX = "lab:idx:products";
    public static final String SEARCH_PREFIX = "lab:search:product:";
    private static final String SEARCH_SEEDED = "lab:search:seeded";
    private static final String JSON_KEY = "lab:json:user:1001";
    private static final String TS_KEY = "lab:ts:greenhouse:temp";
    private static final String TS_COMPACTED = "lab:ts:greenhouse:temp:30m";
    private static final String VECTORS = "lab:vec:colors";

    private final StringRedisTemplate redis;
    private final RedisModules modules;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final int searchDocs;

    public ModulesService(StringRedisTemplate redis, RedisModules modules, JdbcTemplate jdbc, ObjectMapper mapper,
                          @Value("${lab.search.docs}") int searchDocs) {
        this.redis = redis;
        this.modules = modules;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.searchDocs = searchDocs;
    }

    // ---- JSON --------------------------------------------------------------------------

    public DemoResult json() {
        Demo demo = Demo.start("json");
        String doc = """
                {"name":"Ada Lovelace","email":"ada@example.com","visits":0,"tags":["math","poet"],
                 "address":{"city":"London","zip":"W1"},"plan":{"tier":"free","seats":1}}""";
        modules.scalar("JSON.SET", JSON_KEY, "$", doc);
        demo.cmd("JSON.SET " + JSON_KEY + " $ '{\"name\":\"Ada Lovelace\", … }'  → OK");
        Object before = readJson(modules.scalar("JSON.GET", JSON_KEY, "$"));
        Object city = modules.scalar("JSON.GET", JSON_KEY, "$.address.city");
        demo.cmd("JSON.GET " + JSON_KEY + " $.address.city  → " + city);
        Object visits = modules.scalar("JSON.NUMINCRBY", JSON_KEY, "$.visits", 1);
        demo.cmd("JSON.NUMINCRBY " + JSON_KEY + " $.visits 1  → " + visits);
        List<Object> tagCount = modules.list("JSON.ARRAPPEND", JSON_KEY, "$.tags", "\"vip\"");
        demo.cmd("JSON.ARRAPPEND " + JSON_KEY + " $.tags '\"vip\"'  → " + tagCount);
        modules.scalar("JSON.SET", JSON_KEY, "$.plan.tier", "\"pro\"");
        demo.cmd("JSON.SET " + JSON_KEY + " $.plan.tier '\"pro\"'  → OK");
        List<Object> seats = modules.list("JSON.NUMMULTBY", JSON_KEY, "$.plan.seats", 5);
        demo.cmd("JSON.NUMMULTBY " + JSON_KEY + " $.plan.seats 5  → " + seats);
        List<Object> keys = modules.list("JSON.OBJKEYS", JSON_KEY, "$");
        demo.cmd("JSON.OBJKEYS " + JSON_KEY + " $  → " + keys);
        Object after = readJson(modules.scalar("JSON.GET", JSON_KEY, "$"));
        demo.cmd("JSON.GET " + JSON_KEY + " $  → updated document");

        @SuppressWarnings("unchecked")
        Map<String, Object> updated = after instanceof List<?> list && !list.isEmpty() ? (Map<String, Object>) list.get(0) : Map.of();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", JSON_KEY);
        data.put("before", before instanceof List<?> list && !list.isEmpty() ? list.get(0) : before);
        data.put("after", updated);
        data.put("city", readJson(city));
        demo.check("NUMINCRBY changed one number in place", Integer.valueOf(1).equals(updated.get("visits")), "visits = " + updated.get("visits"));
        demo.check("ARRAPPEND added to the nested array", String.valueOf(updated.get("tags")).contains("vip"), String.valueOf(updated.get("tags")));
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) updated.getOrDefault("plan", Map.of());
        demo.check("Path updates touched only their fields", "pro".equals(plan.get("tier")) && Integer.valueOf(5).equals(plan.get("seats")),
                "plan = " + plan);
        return demo.finish(data);
    }

    // ---- Query Engine (search) ---------------------------------------------------------

    public DemoResult search(String text, String category, Double maxPrice) {
        Demo demo = Demo.start("search");
        int indexed = ensureSearchIndex(demo);
        String query = buildQuery(text, category, maxPrice);

        long start = System.nanoTime();
        List<Object> reply = modules.list("FT.SEARCH", SEARCH_INDEX, query, "SORTBY", "price", "ASC", "LIMIT", 0, 10);
        double redisMs = Demo.elapsedMs(start);
        demo.cmd("FT.SEARCH " + SEARCH_INDEX + " \"" + query + "\" SORTBY price ASC LIMIT 0 10  → " + reply.get(0) + " matches");
        long total = (Long) reply.get(0);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (int i = 1; i + 1 < reply.size(); i += 2) {
            // Fields come as name/value pairs; with SORTBY the sort value precedes the "$" document
            if (reply.get(i + 1) instanceof List<?> fields && fields.contains("$")) {
                Object json = readJson(fields.get(fields.indexOf("$") + 1));
                if (json instanceof Map<?, ?> product) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> hit = new LinkedHashMap<>((Map<String, Object>) product);
                    hit.put("key", reply.get(i));
                    hits.add(hit);
                }
            }
        }

        List<Map<String, Object>> facets = new ArrayList<>();
        List<Object> aggregate = modules.list("FT.AGGREGATE", SEARCH_INDEX, query, "GROUPBY", 1, "@category",
                "REDUCE", "COUNT", 0, "AS", "count", "SORTBY", 2, "@count", "DESC");
        for (int i = 1; i < aggregate.size(); i++) {
            if (aggregate.get(i) instanceof List<?> row) {
                Map<String, Object> facet = new LinkedHashMap<>();
                for (int f = 0; f + 1 < row.size(); f += 2) {
                    facet.put(String.valueOf(row.get(f)), row.get(f + 1));
                }
                facets.add(facet);
            }
        }
        demo.cmd("FT.AGGREGATE " + SEARCH_INDEX + " \"" + query + "\" GROUPBY 1 @category REDUCE COUNT 0 AS count  → "
                + facets.size() + " categories");

        // The equivalent SQL scan, for comparison
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM products WHERE id <= ?");
        List<Object> params = new ArrayList<>(List.of(indexed));
        String cleaned = cleanText(text);
        if (!cleaned.isEmpty()) {
            for (String word : cleaned.split(" ")) {
                sql.append(" AND (name ILIKE ? OR description ILIKE ?)");
                params.add("%" + word + "%");
                params.add("%" + word + "%");
            }
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category);
        }
        if (maxPrice != null) {
            sql.append(" AND price <= ?");
            params.add(maxPrice);
        }
        start = System.nanoTime();
        Long sqlCount = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        double postgresMs = Demo.elapsedMs(start);
        demo.cmd("SQL: " + sql.toString().replace("?", "…") + "  → " + sqlCount + " rows (" + postgresMs + " ms)");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("indexedDocs", indexed);
        data.put("total", total);
        data.put("hits", hits);
        data.put("facets", facets);
        data.put("redisMs", redisMs);
        data.put("postgresMs", postgresMs);
        data.put("postgresCount", sqlCount);
        boolean filtersHold = hits.stream().allMatch(h ->
                (category == null || category.isBlank() || category.equals(h.get("category")))
                        && (maxPrice == null || ((Number) h.get("price")).doubleValue() <= maxPrice));
        boolean sorted = true;
        for (int i = 1; i < hits.size(); i++) {
            sorted &= ((Number) hits.get(i - 1).get("price")).doubleValue() <= ((Number) hits.get(i).get("price")).doubleValue();
        }
        demo.check("Every hit satisfies the tag and numeric filters", filtersHold, hits.size() + " hits checked");
        demo.check("SORTBY price returned cheapest first", sorted, "ascending");
        demo.check("The index found matches", total > 0, total + " documents");
        return demo.finish(data);
    }

    /** Creates the index first, then loads documents, so they are indexed synchronously as they are written. */
    private int ensureSearchIndex(Demo demo) {
        try {
            modules.scalar("FT.CREATE", SEARCH_INDEX, "ON", "JSON", "PREFIX", 1, SEARCH_PREFIX, "SCHEMA",
                    "$.name", "AS", "name", "TEXT", "WEIGHT", 2,
                    "$.description", "AS", "description", "TEXT",
                    "$.category", "AS", "category", "TAG",
                    "$.price", "AS", "price", "NUMERIC", "SORTABLE",
                    "$.avgRating", "AS", "rating", "NUMERIC");
            demo.cmd("FT.CREATE " + SEARCH_INDEX + " ON JSON PREFIX 1 " + SEARCH_PREFIX
                    + " SCHEMA $.name AS name TEXT WEIGHT 2 $.description AS description TEXT $.category AS category TAG"
                    + " $.price AS price NUMERIC SORTABLE $.avgRating AS rating NUMERIC  → OK");
        } catch (RedisCommandExecutionException e) {
            if (!String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("already exists")) {
                throw e;
            }
        }
        String seeded = redis.opsForValue().get(SEARCH_SEEDED);
        if (seeded != null) {
            return Integer.parseInt(seeded);
        }
        List<Product> products = jdbc.query(
                "SELECT id, sku, name, category, price, stock, description, 0 AS review_count, 0 AS avg_rating "
                        + "FROM products WHERE id <= ? ORDER BY id", CatalogService.PRODUCT_MAPPER, searchDocs);
        List<Object[]> commands = new ArrayList<>(products.size());
        for (Product product : products) {
            commands.add(new Object[]{"JSON.SET", SEARCH_PREFIX + product.id(), "$", writeJson(product)});
        }
        for (int from = 0; from < commands.size(); from += 2000) {
            modules.pipeline(commands.subList(from, Math.min(commands.size(), from + 2000)));
        }
        redis.opsForValue().set(SEARCH_SEEDED, String.valueOf(products.size()));
        demo.cmd("JSON.SET " + SEARCH_PREFIX + "<id> $ '{…}'  ×" + products.size() + " (copied from Postgres, pipelined)");
        return products.size();
    }

    static String buildQuery(String text, String category, Double maxPrice) {
        List<String> parts = new ArrayList<>();
        String cleaned = cleanText(text);
        if (!cleaned.isEmpty()) {
            List<String> terms = new ArrayList<>();
            for (String word : cleaned.split(" ")) {
                terms.add(word.length() >= 2 ? word + "*" : word);
            }
            parts.add("(" + String.join(" ", terms) + ")");
        }
        if (category != null && !category.isBlank()) {
            parts.add("@category:{" + category.replaceAll("[^A-Za-z]", "") + "}");
        }
        if (maxPrice != null) {
            parts.add("@price:[0 " + maxPrice + "]");
        }
        return parts.isEmpty() ? "*" : String.join(" ", parts);
    }

    /** Keeps letters, digits and single spaces, so user text cannot inject query syntax. */
    static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().replaceAll(" +", " ");
    }

    // ---- Time series -------------------------------------------------------------------

    public DemoResult timeSeries(int hours) {
        Demo demo = Demo.start("timeseries");
        redis.delete(List.of(TS_KEY, TS_COMPACTED));
        modules.scalar("TS.CREATE", TS_KEY, "RETENTION", 7L * 24 * 3600 * 1000, "DUPLICATE_POLICY", "LAST",
                "LABELS", "sensor", "greenhouse", "unit", "celsius");
        modules.scalar("TS.CREATE", TS_COMPACTED, "LABELS", "sensor", "greenhouse", "unit", "celsius", "resolution", "30m");
        modules.scalar("TS.CREATERULE", TS_KEY, TS_COMPACTED, "AGGREGATION", "avg", 1_800_000);
        demo.cmd("TS.CREATE " + TS_KEY + " RETENTION 604800000 LABELS sensor greenhouse unit celsius  → OK");
        demo.cmd("TS.CREATE " + TS_COMPACTED + " LABELS … resolution 30m  → OK");
        demo.cmd("TS.CREATERULE " + TS_KEY + " " + TS_COMPACTED + " AGGREGATION avg 1800000  → OK");

        long now = System.currentTimeMillis() / 60_000 * 60_000;
        int points = hours * 60;
        List<Object> args = new ArrayList<>();
        java.util.Random random = new java.util.Random();
        for (int i = points; i > 0; i--) {
            long ts = now - (long) i * 60_000;
            double hourOfDay = ((ts / 3_600_000.0) % 24);
            double temp = 21 + 6 * Math.sin((hourOfDay - 9) / 24 * 2 * Math.PI) + random.nextGaussian() * 0.6;
            args.add(TS_KEY);
            args.add(ts);
            args.add(Math.round(temp * 100) / 100.0);
        }
        modules.list("TS.MADD", args.toArray());
        demo.cmd("TS.MADD " + TS_KEY + " <ts> <°C> …  (" + points + " samples, one per minute)");

        List<double[]> raw = toPoints(modules.list("TS.RANGE", TS_KEY, "-", "+"));
        demo.cmd("TS.RANGE " + TS_KEY + " - +  → " + raw.size() + " samples");
        List<double[]> avg = toPoints(modules.list("TS.RANGE", TS_KEY, "-", "+", "AGGREGATION", "avg", 1_800_000));
        demo.cmd("TS.RANGE " + TS_KEY + " - + AGGREGATION avg 1800000  → " + avg.size() + " buckets");
        List<double[]> max = toPoints(modules.list("TS.RANGE", TS_KEY, "-", "+", "AGGREGATION", "max", 1_800_000));
        demo.cmd("TS.RANGE " + TS_KEY + " - + AGGREGATION max 1800000  → " + max.size() + " buckets");
        List<double[]> compacted = toPoints(modules.list("TS.RANGE", TS_COMPACTED, "-", "+"));
        demo.cmd("TS.RANGE " + TS_COMPACTED + " - +  → " + compacted.size() + " buckets written by the rule");
        List<Object> latest = modules.list("TS.GET", TS_KEY);
        demo.cmd("TS.GET " + TS_KEY + "  → " + latest);
        List<Object> byLabel = modules.list("TS.QUERYINDEX", "sensor=greenhouse");
        demo.cmd("TS.QUERYINDEX sensor=greenhouse  → " + byLabel);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("raw", raw);
        data.put("avg30m", avg);
        data.put("max30m", max);
        data.put("compacted", compacted);
        data.put("seriesByLabel", byLabel);
        demo.check("Every sample was stored", raw.size() == points, raw.size() + "/" + points);
        demo.check("Server-side downsampling to 30-minute buckets", !avg.isEmpty() && avg.size() <= hours * 2 + 1,
                points + " samples → " + avg.size() + " buckets");
        boolean avgBelowMax = true;
        for (int i = 0; i < Math.min(avg.size(), max.size()); i++) {
            avgBelowMax &= avg.get(i)[1] <= max.get(i)[1] + 1e-9;
        }
        demo.check("Each bucket's average ≤ its maximum", avgBelowMax, "aggregations agree");
        demo.check("The compaction rule filled the 30m series", !compacted.isEmpty(), compacted.size() + " closed buckets");
        return demo.finish(data);
    }

    private static List<double[]> toPoints(List<Object> reply) {
        List<double[]> points = new ArrayList<>();
        for (Object sample : reply) {
            if (sample instanceof List<?> pair && pair.size() == 2) {
                points.add(new double[]{((Number) pair.get(0)).doubleValue(), Double.parseDouble(String.valueOf(pair.get(1)))});
            }
        }
        return points;
    }

    // ---- Vector sets -------------------------------------------------------------------

    /** Finds colours similar to the query colour; each colour is a 4-dimensional embedding of its HSL values. */
    public DemoResult vectors(String hex, boolean onlyLight) {
        Demo demo = Demo.start("vectors");
        String query = hex != null && hex.matches("#?[0-9a-fA-F]{6}") ? (hex.startsWith("#") ? hex : "#" + hex).toLowerCase(Locale.ROOT) : "#e4572e";
        Long size = (Long) modules.scalar("VCARD", VECTORS);
        if (size == null || size == 0) {
            List<Object[]> adds = new ArrayList<>();
            for (int hue = 0; hue < 360; hue += 15) {
                for (double saturation : new double[]{0.85, 0.45}) {
                    for (double lightness : new double[]{0.3, 0.5, 0.72}) {
                        String color = hslToHex(hue, saturation, lightness);
                        double[] v = embed(hue, saturation, lightness);
                        adds.add(new Object[]{"VADD", VECTORS, "VALUES", 4, v[0], v[1], v[2], v[3], color, "NOQUANT",
                                "SETATTR", String.format(Locale.ROOT, "{\"h\":%d,\"s\":%.2f,\"l\":%.2f}", hue, saturation, lightness)});
                    }
                }
            }
            modules.pipeline(adds);
            size = (Long) modules.scalar("VCARD", VECTORS);
            demo.cmd("VADD " + VECTORS + " VALUES 4 <x> <y> <z> <w> <#hex> NOQUANT SETATTR '{\"h\":…,\"s\":…,\"l\":…}'  ×" + adds.size());
        }
        double[] hsl = hexToHsl(query);
        double[] v = embed(hsl[0], hsl[1], hsl[2]);
        List<Object> command = new ArrayList<>(List.of(VECTORS, "VALUES", 4, v[0], v[1], v[2], v[3], "WITHSCORES", "COUNT", 8));
        if (onlyLight) {
            command.add("FILTER");
            command.add(".l >= 0.6");
        }
        List<Object> reply = modules.list("VSIM", command.toArray());
        demo.cmd(String.format(Locale.ROOT, "VSIM %s VALUES 4 %.3f %.3f %.3f %.3f WITHSCORES COUNT 8%s  → %d results",
                VECTORS, v[0], v[1], v[2], v[3], onlyLight ? " FILTER '.l >= 0.6'" : "", reply.size() / 2));
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = 0; i + 1 < reply.size(); i += 2) {
            matches.add(Map.of("color", reply.get(i), "score", Double.parseDouble(String.valueOf(reply.get(i + 1)))));
        }
        Object dim = modules.scalar("VDIM", VECTORS);
        demo.cmd("VCARD " + VECTORS + "  → " + size + "; VDIM " + VECTORS + "  → " + dim);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("embedding", v);
        data.put("matches", matches);
        data.put("setSize", size);
        data.put("onlyLight", onlyLight);
        boolean sorted = true;
        for (int i = 1; i < matches.size(); i++) {
            sorted &= (double) matches.get(i - 1).get("score") >= (double) matches.get(i).get("score");
        }
        demo.check("Results are ranked by similarity", sorted, matches.size() + " neighbours");
        demo.check("The closest colour is a strong match", !matches.isEmpty() && (double) matches.get(0).get("score") > 0.9,
                matches.isEmpty() ? "none" : "score " + matches.get(0).get("score"));
        if (onlyLight) {
            boolean allLight = matches.stream().allMatch(m -> hexToHsl((String) m.get("color"))[2] >= 0.55);
            demo.check("FILTER kept only light colours", allLight, "attribute filter .l >= 0.6");
        }
        return demo.finish(data);
    }

    /** Hue on a circle (so 350° is near 10°), scaled by saturation; lightness on its own axis; a constant avoids zero vectors. */
    static double[] embed(double hue, double saturation, double lightness) {
        double radians = Math.toRadians(hue);
        return new double[]{
                Math.cos(radians) * saturation,
                Math.sin(radians) * saturation,
                (lightness - 0.5) * 1.5,
                0.35};
    }

    static String hslToHex(double h, double s, double l) {
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double x = c * (1 - Math.abs((h / 60) % 2 - 1));
        double m = l - c / 2;
        double r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return String.format("#%02x%02x%02x", Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }

    static double[] hexToHsl(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double r = ((rgb >> 16) & 0xff) / 255.0;
        double g = ((rgb >> 8) & 0xff) / 255.0;
        double b = (rgb & 0xff) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2;
        double d = max - min;
        if (d == 0) {
            return new double[]{0, 0, l};
        }
        double s = d / (1 - Math.abs(2 * l - 1));
        double h;
        if (max == r) {
            h = 60 * (((g - b) / d) % 6);
        } else if (max == g) {
            h = 60 * ((b - r) / d + 2);
        } else {
            h = 60 * ((r - g) / d + 4);
        }
        return new double[]{h < 0 ? h + 360 : h, s, l};
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object readJson(Object json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(String.valueOf(json), Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
