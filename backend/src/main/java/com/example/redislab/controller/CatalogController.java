package com.example.redislab.controller;

import com.example.redislab.model.DemoResult;
import com.example.redislab.model.Product;
import com.example.redislab.service.CatalogService;
import com.example.redislab.service.CatalogService.CatalogPayload;
import com.example.redislab.service.ProductService;
import com.example.redislab.support.Demo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Postgres vs Redis. The catalog endpoints return the raw JSON array as the body and describe how it
 * was produced in headers, so the cached path can send stored bytes without re-serializing them.
 */
@RestController
public class CatalogController {

    private final CatalogService catalog;
    private final ProductService products;
    private final StringRedisTemplate redis;

    public CatalogController(CatalogService catalog, ProductService products, StringRedisTemplate redis) {
        this.catalog = catalog;
        this.products = products;
        this.redis = redis;
    }

    @GetMapping("/api/catalog/db")
    public ResponseEntity<byte[]> fromDatabase(@RequestParam(defaultValue = "10000") int size) {
        return respond(catalog.fromDatabase(catalog.clampSize(size)));
    }

    @GetMapping("/api/catalog/cache")
    public ResponseEntity<byte[]> fromCache(@RequestParam(defaultValue = "10000") int size) {
        return respond(catalog.fromCache(catalog.clampSize(size)));
    }

    @DeleteMapping("/api/catalog/cache")
    public DemoResult evictCatalog() {
        Demo demo = Demo.start("catalog-evict");
        long removed = catalog.evict();
        demo.cmd("SCAN 0 MATCH " + CatalogService.KEY_PREFIX + "* + UNLINK  → " + removed + " keys");
        demo.check("Cached catalog removed", true, removed + " keys");
        return demo.finish(Map.of("removed", removed));
    }

    @PostMapping("/api/catalog/benchmark")
    public DemoResult benchmark(@RequestParam(defaultValue = "10000") int size,
                                @RequestParam(defaultValue = "5") int iterations) {
        return catalog.benchmark(catalog.clampSize(size), Math.max(1, Math.min(iterations, 10)));
    }

    private static ResponseEntity<byte[]> respond(CatalogPayload payload) {
        String serverTiming = payload.timings().entrySet().stream()
                .map(e -> e.getKey() + ";dur=" + e.getValue())
                .collect(Collectors.joining(", "));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Source", payload.source())
                .header("X-Cache", payload.cacheStatus())
                .header("X-Rows", String.valueOf(payload.rows()))
                .header("X-Bytes", String.valueOf(payload.body().length))
                .header("X-Sha256", payload.sha256())
                .header("Server-Timing", serverTiming)
                .body(payload.body());
    }

    // ---- Spring @Cacheable -------------------------------------------------------------

    @GetMapping("/api/spring-cache/products/{id}")
    public DemoResult cachedProduct(@PathVariable int id) {
        Demo demo = Demo.start("spring-cache");
        String key = ProductService.CACHE_KEY_PREFIX + id;
        boolean wasCached = Boolean.TRUE.equals(redis.hasKey(key));
        long start = System.nanoTime();
        Product product = products.findProduct(id);
        double ms = Demo.elapsedMs(start);
        if (wasCached) {
            demo.cmd("GET " + key + "  → HIT, method body skipped");
        } else {
            demo.cmd("GET " + key + "  → (nil), cache miss");
            demo.cmd("SQL: SELECT … FROM products LEFT JOIN LATERAL (SELECT count(*), avg(rating) FROM reviews …) WHERE id = " + id);
            demo.cmd("SET " + key + " '{…product json…}' PX 60000");
        }
        Long ttl = redis.getExpire(key);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", product);
        data.put("cacheHit", wasCached);
        data.put("ms", ms);
        data.put("key", key);
        data.put("ttlSeconds", ttl);
        data.put("cachedJson", redis.opsForValue().get(key));
        demo.check("@Cacheable stored the result in Redis", Boolean.TRUE.equals(redis.hasKey(key)), key);
        demo.check("The entry has a TTL", ttl != null && ttl > 0, ttl + " s");
        return demo.finish(data);
    }

    @PutMapping("/api/spring-cache/products/{id}/price")
    public DemoResult updatePrice(@PathVariable int id, @RequestParam double price) {
        Demo demo = Demo.start("spring-cache-put");
        String key = ProductService.CACHE_KEY_PREFIX + id;
        double safePrice = Math.round(Math.max(0.01, Math.min(price, 9999)) * 100) / 100.0;
        Product updated = products.updatePrice(id, safePrice);
        demo.cmd("SQL: UPDATE products SET price = " + safePrice + " WHERE id = " + id);
        demo.cmd("SET " + key + " '{…updated json…}' PX 60000  (@CachePut)");
        // The cached catalog pages contain this price too, so they are now stale
        long staleKeys = catalog.evict();
        demo.cmd("SCAN 0 MATCH " + CatalogService.KEY_PREFIX + "* + UNLINK  → " + staleKeys + " stale catalog keys");
        String cached = redis.opsForValue().get(key);
        demo.check("Cache holds the new price immediately", cached != null && cached.contains(String.valueOf(safePrice)),
                "price " + safePrice);
        demo.check("Cached catalog pages were invalidated", catalog.evict() == 0, staleKeys + " keys removed");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", updated);
        data.put("key", key);
        data.put("cachedJson", cached);
        return demo.finish(data);
    }

    @PostMapping("/api/spring-cache/products/{id}/evict")
    public DemoResult evictProduct(@PathVariable int id) {
        Demo demo = Demo.start("spring-cache-evict");
        String key = ProductService.CACHE_KEY_PREFIX + id;
        products.evict(id);
        demo.cmd("DEL " + key + "  (@CacheEvict)");
        boolean gone = !Boolean.TRUE.equals(redis.hasKey(key));
        demo.check("Entry removed; the next read goes to Postgres", gone, key);
        return demo.finish(Map.of("key", key, "evicted", gone));
    }
}
