package com.example.redislab.service;

import com.example.redislab.model.Product;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/** Spring's cache abstraction backed by Redis: the annotations do the GET/SET/DEL for us. */
@Service
public class ProductService {

    public static final String CACHE_KEY_PREFIX = "lab:spring:product::";

    private static final String PRODUCT_SQL = """
            SELECT p.id, p.sku, p.name, p.category, p.price, p.stock, p.description,
                   r.review_count, COALESCE(r.avg_rating, 0) AS avg_rating
            FROM products p
            LEFT JOIN LATERAL (SELECT count(*) AS review_count, round(avg(rating), 2) AS avg_rating
                               FROM reviews WHERE product_id = p.id) r ON true
            WHERE p.id = ?
            """;

    private final JdbcTemplate jdbc;

    public ProductService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Cacheable(cacheNames = "product", key = "#id")
    public Product findProduct(int id) {
        return load(id);
    }

    /** Write-through: update Postgres, then put the fresh row into the cache. */
    @CachePut(cacheNames = "product", key = "#id")
    public Product updatePrice(int id, double price) {
        jdbc.update("UPDATE products SET price = ? WHERE id = ?", price, id);
        return load(id);
    }

    @CacheEvict(cacheNames = "product", key = "#id")
    public void evict(int id) {
        // The annotation removes lab:spring:product::<id>
    }

    private Product load(int id) {
        return jdbc.query(PRODUCT_SQL, CatalogService.PRODUCT_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("No product " + id));
    }
}
