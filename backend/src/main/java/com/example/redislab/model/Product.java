package com.example.redislab.model;

/** One catalog row: a product joined with its aggregated review stats. */
public record Product(
        int id,
        String sku,
        String name,
        String category,
        double price,
        int stock,
        String description,
        long reviewCount,
        double avgRating) {
}
