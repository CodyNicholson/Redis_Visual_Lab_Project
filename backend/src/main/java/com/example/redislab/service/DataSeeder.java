package com.example.redislab.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fills Postgres with deterministic demo data on first start. Values come from a multiplicative
 * hash of the row number instead of random(), so every environment gets the same catalog.
 * Multiplying two hashes skews reviews toward low product ids, so popularity varies like real data.
 */
@Component
@Order(1)
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final String INSERT_PRODUCTS = """
            INSERT INTO products (id, sku, name, category, price, stock, description, created_at)
            SELECT i,
                   'SKU-' || lpad(i::text, 6, '0'),
                   adj || ' ' || material || ' ' || noun,
                   (ARRAY['Home','Office','Outdoors','Electronics','Kitchen','Fashion','Sports','Books'])[1 + (i * 7) % 8],
                   5 + (i::bigint * 2654435761 % 49500) / 100.0,
                   (i::bigint * 40503 % 500)::int,
                   'A ' || lower(adj) || ' ' || lower(noun) || ' crafted from ' || lower(material) || '. ' ||
                   (ARRAY['Great for everyday use.','Built to last a lifetime.','A perfect gift idea.',
                          'Loved by thousands of customers.','Limited edition release.'])[1 + i % 5],
                   now() - ((i % 365) * interval '1 day')
            FROM (
                SELECT i,
                       (ARRAY['Rustic','Sleek','Ergonomic','Vintage','Smart','Compact','Deluxe','Eco',
                              'Rugged','Classic','Modern','Portable'])[1 + i % 12] AS adj,
                       (ARRAY['Oak','Steel','Cotton','Leather','Bamboo','Granite','Wool','Copper',
                              'Glass','Carbon'])[1 + (i / 12) % 10] AS material,
                       (ARRAY['Chair','Lamp','Backpack','Keyboard','Kettle','Speaker','Jacket','Desk','Watch',
                              'Mug','Headphones','Bicycle','Notebook','Blender','Tent'])[1 + (i / 120) % 15] AS noun
                FROM generate_series(1, ?) AS i
            ) AS g
            """;

    private static final String INSERT_REVIEWS = """
            INSERT INTO reviews (product_id, rating, created_at)
            SELECT 1 + (g::bigint * 2654435761 % ?) * (g::bigint * 2246822519 % ?) / ?,
                   (ARRAY[5, 5, 5, 4, 4, 4, 3, 3, 2, 1])[1 + (g::bigint * 40503 + g / 7) % 10],
                   now() - ((g % 1000) * interval '1 hour')
            FROM generate_series(1, ?) AS g
            """;

    private final JdbcTemplate jdbc;
    private final int products;
    private final int reviews;

    public DataSeeder(JdbcTemplate jdbc,
                      @Value("${lab.seed.products}") int products,
                      @Value("${lab.seed.reviews}") int reviews) {
        this.jdbc = jdbc;
        this.products = products;
        this.reviews = reviews;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
        if (existing != null && existing > 0) {
            log.info("Postgres already seeded with {} products", existing);
            return;
        }
        long start = System.currentTimeMillis();
        jdbc.update(INSERT_PRODUCTS, products);
        jdbc.update(INSERT_REVIEWS, products, products, products, reviews);
        jdbc.execute("ANALYZE products");
        jdbc.execute("ANALYZE reviews");
        log.info("Seeded {} products and {} reviews in {} ms", products, reviews, System.currentTimeMillis() - start);
    }
}
