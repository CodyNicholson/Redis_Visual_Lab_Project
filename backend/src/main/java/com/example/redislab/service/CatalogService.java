package com.example.redislab.service;

import com.example.redislab.model.DemoResult;
import com.example.redislab.model.Product;
import com.example.redislab.support.Demo;
import com.example.redislab.support.KeyScanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The headline demo: the same large catalog served straight from Postgres or through a
 * cache-aside Redis layer. Both paths return byte-identical JSON, proven by a SHA-256 hash.
 */
@Service
public class CatalogService {

    public static final String KEY_PREFIX = "lab:catalog:";

    static final String CATALOG_SQL = """
            SELECT p.id, p.sku, p.name, p.category, p.price, p.stock, p.description,
                   COALESCE(r.review_count, 0) AS review_count, COALESCE(r.avg_rating, 0) AS avg_rating
            FROM products p
            LEFT JOIN (SELECT product_id, count(*) AS review_count, round(avg(rating), 2) AS avg_rating
                       FROM reviews GROUP BY product_id) r ON r.product_id = p.id
            WHERE p.id <= ?
            ORDER BY p.id
            """;

    static final RowMapper<Product> PRODUCT_MAPPER = (rs, i) -> new Product(
            rs.getInt("id"), rs.getString("sku"), rs.getString("name"), rs.getString("category"),
            rs.getDouble("price"), rs.getInt("stock"), rs.getString("description"),
            rs.getLong("review_count"), rs.getDouble("avg_rating"));

    /** The response body plus how it was produced. Timings are in milliseconds, in insertion order. */
    public record CatalogPayload(byte[] body, int rows, String sha256, String source, String cacheStatus,
                                 Map<String, Double> timings) {
    }

    private final JdbcTemplate jdbc;
    private final RedisTemplate<String, byte[]> bytesRedis;
    private final KeyScanner keys;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public CatalogService(JdbcTemplate jdbc, RedisTemplate<String, byte[]> bytesRedisTemplate,
                          KeyScanner keys, ObjectMapper mapper,
                          @Value("${lab.catalog.cache-ttl}") Duration ttl) {
        this.jdbc = jdbc;
        this.bytesRedis = bytesRedisTemplate;
        this.keys = keys;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    public int productCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
        return count == null ? 0 : count;
    }

    public int clampSize(int size) {
        return Math.max(1, Math.min(size, productCount()));
    }

    /** Always runs the aggregate query and serializes the rows. */
    public CatalogPayload fromDatabase(int size) {
        long start = System.nanoTime();
        List<Product> rows = jdbc.query(CATALOG_SQL, PRODUCT_MAPPER, size);
        double queryMs = Demo.elapsedMs(start);
        long serializeStart = System.nanoTime();
        byte[] body = toJson(rows);
        double serializeMs = Demo.elapsedMs(serializeStart);
        Map<String, Double> timings = new LinkedHashMap<>();
        timings.put("db", queryMs);
        timings.put("serialize", serializeMs);
        timings.put("total", Demo.elapsedMs(start));
        return new CatalogPayload(body, rows.size(), sha256(body), "postgres", "BYPASS", timings);
    }

    /** Cache-aside: read Redis first; on a miss load from Postgres and store the bytes with a TTL. */
    public CatalogPayload fromCache(int size) {
        long start = System.nanoTime();
        String key = KEY_PREFIX + size;
        List<byte[]> cached = bytesRedis.opsForValue().multiGet(List.of(key, key + ":meta"));
        double readMs = Demo.elapsedMs(start);
        if (cached != null && cached.get(0) != null && cached.get(1) != null) {
            String[] meta = new String(cached.get(1), StandardCharsets.UTF_8).split(":");
            Map<String, Double> timings = new LinkedHashMap<>();
            timings.put("redis", readMs);
            timings.put("total", Demo.elapsedMs(start));
            return new CatalogPayload(cached.get(0), Integer.parseInt(meta[1]), meta[0], "redis", "HIT", timings);
        }
        CatalogPayload loaded = fromDatabase(size);
        long writeStart = System.nanoTime();
        bytesRedis.opsForValue().set(key, loaded.body(), ttl);
        bytesRedis.opsForValue().set(key + ":meta",
                (loaded.sha256() + ":" + loaded.rows()).getBytes(StandardCharsets.UTF_8), ttl);
        Map<String, Double> timings = new LinkedHashMap<>();
        timings.put("redis", readMs);
        timings.put("db", loaded.timings().get("db"));
        timings.put("serialize", loaded.timings().get("serialize"));
        timings.put("redisWrite", Demo.elapsedMs(writeStart));
        timings.put("total", Demo.elapsedMs(start));
        return new CatalogPayload(loaded.body(), loaded.rows(), loaded.sha256(), "postgres+redis", "MISS", timings);
    }

    public long evict() {
        return keys.unlink(KEY_PREFIX + "*");
    }

    /** Times both paths several times on the server, so network transfer to the browser is excluded. */
    public DemoResult benchmark(int size, int iterations) {
        Demo demo = Demo.start("catalog-benchmark");
        CatalogPayload warm = fromCache(size);
        demo.cmd("MGET " + KEY_PREFIX + size + " " + KEY_PREFIX + size + ":meta   → " + warm.cacheStatus() + " (warm-up)");
        List<Double> dbTimes = new ArrayList<>();
        List<Double> cacheTimes = new ArrayList<>();
        boolean hashesMatch = true;
        boolean allHits = true;
        for (int i = 0; i < iterations; i++) {
            CatalogPayload db = fromDatabase(size);
            CatalogPayload cache = fromCache(size);
            dbTimes.add(db.timings().get("total"));
            cacheTimes.add(cache.timings().get("total"));
            hashesMatch &= db.sha256().equals(cache.sha256());
            allHits &= "HIT".equals(cache.cacheStatus());
            demo.cmd("SQL catalog query (" + db.timings().get("total") + " ms)  vs  MGET " + KEY_PREFIX + size
                    + " (" + cache.timings().get("total") + " ms)");
        }
        double dbAvg = average(dbTimes);
        double cacheAvg = average(cacheTimes);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", warm.rows());
        data.put("bytes", warm.body().length);
        data.put("sha256", warm.sha256());
        data.put("iterations", iterations);
        data.put("dbMs", dbTimes);
        data.put("cacheMs", cacheTimes);
        data.put("dbAvgMs", dbAvg);
        data.put("cacheAvgMs", cacheAvg);
        data.put("speedup", cacheAvg == 0 ? null : Math.round(dbAvg / cacheAvg * 10) / 10.0);
        demo.check("Postgres and Redis returned byte-identical payloads", hashesMatch, "sha256 " + warm.sha256().substring(0, 16) + "…");
        demo.check("Every timed cache read was a HIT", allHits, iterations + " reads");
        demo.check("Redis average beat Postgres average", cacheAvg < dbAvg,
                String.format("%.2f ms vs %.2f ms", cacheAvg, dbAvg));
        return demo.finish(data);
    }

    private byte[] toJson(List<Product> rows) {
        try {
            return mapper.writeValueAsBytes(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double average(List<Double> values) {
        return Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100) / 100.0;
    }
}
