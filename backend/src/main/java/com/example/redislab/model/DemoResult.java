package com.example.redislab.model;

import java.util.List;

/**
 * What every lab endpoint returns: the Redis commands it ran, a demo-specific data payload,
 * and the self-checks that prove the feature behaved as described.
 */
public record DemoResult(String demo, List<String> commands, Object data, List<Check> checks, double elapsedMs) {

    public record Check(String name, boolean passed, String detail) {
    }

    public boolean allPassed() {
        return checks.stream().allMatch(Check::passed);
    }
}
