package com.example.redislab.support;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Walks the keyspace with SCAN (never KEYS, which blocks Redis while it reads every key). */
@Component
public class KeyScanner {

    private final StringRedisTemplate redis;

    public KeyScanner(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void scan(String pattern, Consumer<String> action) {
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(1000).build())) {
            cursor.forEachRemaining(action);
        }
    }

    /** Deletes every key matching the pattern with UNLINK (memory is freed in the background). */
    public long unlink(String pattern) {
        List<String> batch = new ArrayList<>();
        long[] removed = {0};
        scan(pattern, key -> {
            batch.add(key);
            if (batch.size() == 500) {
                removed[0] += unlinkBatch(batch);
            }
        });
        removed[0] += unlinkBatch(batch);
        return removed[0];
    }

    private long unlinkBatch(List<String> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        Long count = redis.unlink(batch);
        batch.clear();
        return count == null ? 0 : count;
    }
}
