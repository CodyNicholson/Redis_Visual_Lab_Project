package com.example.redislab.service;

import com.example.redislab.model.DemoResult;
import com.example.redislab.support.Demo;
import com.example.redislab.support.RedisModules;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Space-efficient structures: HyperLogLog, bitmaps and Bloom filters. */
@Service
public class ProbabilisticService {

    private static final String BLOOM = "lab:bloom:usernames";

    private final StringRedisTemplate redis;
    private final RedisTemplate<String, byte[]> bytesRedis;
    private final RedisModules modules;

    public ProbabilisticService(StringRedisTemplate redis, RedisTemplate<String, byte[]> bytesRedisTemplate,
                                RedisModules modules) {
        this.redis = redis;
        this.bytesRedis = bytesRedisTemplate;
        this.modules = modules;
    }

    // ---- HyperLogLog -------------------------------------------------------------------

    public DemoResult hyperLogLog(int visits) {
        Demo demo = Demo.start("hyperloglog");
        String hll = "lab:hll:visitors";
        String exact = "lab:hll:visitors:exact-set";
        redis.delete(List.of(hll, exact));
        Random random = new Random();
        int pool = Math.max(1, visits / 2);
        Set<String> truth = new HashSet<>();
        List<String> batch = new ArrayList<>(1000);
        for (int i = 0; i < visits; i++) {
            String visitor = "v" + random.nextInt(pool);
            truth.add(visitor);
            batch.add(visitor);
            if (batch.size() == 1000 || i == visits - 1) {
                String[] members = batch.toArray(String[]::new);
                redis.executePipelined((RedisCallback<Object>) connection -> {
                    byte[][] raw = new byte[members.length][];
                    for (int m = 0; m < members.length; m++) {
                        raw[m] = members[m].getBytes(StandardCharsets.UTF_8);
                    }
                    connection.hyperLogLogCommands().pfAdd(hll.getBytes(StandardCharsets.UTF_8), raw);
                    connection.setCommands().sAdd(exact.getBytes(StandardCharsets.UTF_8), raw);
                    return null;
                });
                batch.clear();
            }
        }
        demo.cmd("PFADD " + hll + " v… v… (" + visits + " visits in batches of 1000, pipelined)");
        demo.cmd("SADD " + exact + " v… v… (same visits, for comparison)");
        Long estimate = redis.opsForHyperLogLog().size(hll);
        Long exactCount = redis.opsForSet().size(exact);
        long hllBytes = memoryUsage(hll);
        long setBytes = memoryUsage(exact);
        demo.cmd("PFCOUNT " + hll + "  → " + estimate);
        demo.cmd("SCARD " + exact + "  → " + exactCount);
        demo.cmd("MEMORY USAGE " + hll + "  → " + hllBytes + " bytes");
        demo.cmd("MEMORY USAGE " + exact + "  → " + setBytes + " bytes");
        redis.unlink(exact);
        double errorPct = Math.abs(estimate - truth.size()) * 100.0 / truth.size();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("visits", visits);
        data.put("estimate", estimate);
        data.put("exact", exactCount);
        data.put("errorPct", Math.round(errorPct * 100) / 100.0);
        data.put("hllBytes", hllBytes);
        data.put("setBytes", setBytes);
        demo.check("Estimate within 2% of the true count", errorPct < 2.0, String.format("%.2f%% error", errorPct));
        demo.check("HyperLogLog stays at a fixed ~12 KB", hllBytes <= 16 * 1024, hllBytes + " bytes");
        demo.check("HyperLogLog is far smaller than the exact set", hllBytes * 5 < setBytes,
                hllBytes + " vs " + setBytes + " bytes");
        return demo.finish(data);
    }

    // ---- Bitmaps -----------------------------------------------------------------------

    public DemoResult bitmaps(int users) {
        Demo demo = Demo.start("bitmaps");
        String[] days = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};
        double[] activity = {0.82, 0.78, 0.8, 0.75, 0.7, 0.55, 0.5};
        Random random = new Random();
        List<String> keys = new ArrayList<>();
        int[] expectedCounts = new int[days.length];
        for (int d = 0; d < days.length; d++) {
            String key = "lab:bitmap:active:" + days[d];
            keys.add(key);
            redis.delete(key);
            List<Integer> active = new ArrayList<>();
            for (int u = 0; u < users; u++) {
                if (random.nextDouble() < activity[d]) {
                    active.add(u);
                }
            }
            expectedCounts[d] = active.size();
            byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (int u : active) {
                    connection.stringCommands().setBit(rawKey, u, true);
                }
                return null;
            });
            demo.cmd("SETBIT " + key + " <userId> 1  ×" + active.size());
        }
        List<Long> dailyCounts = new ArrayList<>();
        for (String key : keys) {
            Long count = (Long) modules.scalar("BITCOUNT", key);
            dailyCounts.add(count);
            demo.cmd("BITCOUNT " + key + "  → " + count);
        }
        List<Object> andArgs = new ArrayList<>(List.of("AND", "lab:bitmap:active:all-week"));
        andArgs.addAll(keys);
        modules.scalar("BITOP", andArgs.toArray());
        List<Object> orArgs = new ArrayList<>(List.of("OR", "lab:bitmap:active:any-day"));
        orArgs.addAll(keys);
        modules.scalar("BITOP", orArgs.toArray());
        Long allWeek = (Long) modules.scalar("BITCOUNT", "lab:bitmap:active:all-week");
        Long anyDay = (Long) modules.scalar("BITCOUNT", "lab:bitmap:active:any-day");
        demo.cmd("BITOP AND lab:bitmap:active:all-week mon…sun; BITCOUNT  → " + allWeek);
        demo.cmd("BITOP OR lab:bitmap:active:any-day mon…sun; BITCOUNT  → " + anyDay);
        long bytesPerDay = memoryUsage(keys.get(0));
        demo.cmd("MEMORY USAGE " + keys.get(0) + "  → " + bytesPerDay + " bytes");

        // A bitmap is just a string: read the raw bytes and decode the first 64 users' bits per day
        int shown = Math.min(64, users);
        List<List<Integer>> grid = new ArrayList<>();
        for (String key : keys) {
            byte[] raw = bytesRedis.opsForValue().get(key);
            List<Integer> row = new ArrayList<>();
            for (int u = 0; u < shown; u++) {
                int bit = raw != null && u / 8 < raw.length ? (raw[u / 8] >> (7 - u % 8)) & 1 : 0;
                row.add(bit);
            }
            grid.add(row);
        }
        demo.cmd("GET " + keys.get(0) + "  → raw bytes, decoded bit by bit for the grid");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("users", users);
        data.put("days", days);
        data.put("dailyCounts", dailyCounts);
        data.put("allWeek", allWeek);
        data.put("anyDay", anyDay);
        data.put("bytesPerDay", bytesPerDay);
        data.put("grid", grid);
        boolean countsMatch = true;
        for (int d = 0; d < days.length; d++) {
            countsMatch &= dailyCounts.get(d) == expectedCounts[d];
        }
        long minDaily = dailyCounts.stream().mapToLong(Long::longValue).min().orElse(0);
        long maxDaily = dailyCounts.stream().mapToLong(Long::longValue).max().orElse(0);
        demo.check("BITCOUNT matches the users we flagged", countsMatch, "7 days verified");
        demo.check("AND ≤ every day ≤ OR", allWeek <= minDaily && anyDay >= maxDaily,
                allWeek + " ≤ " + minDaily + " … " + maxDaily + " ≤ " + anyDay);
        demo.check("One bit per user: tiny memory", bytesPerDay < users,
                bytesPerDay + " bytes for " + users + " users");
        return demo.finish(data);
    }

    // ---- Bloom filter ------------------------------------------------------------------

    public DemoResult bloom(int names) {
        Demo demo = Demo.start("bloom");
        redis.delete(BLOOM);
        modules.scalar("BF.RESERVE", BLOOM, "0.01", names);
        demo.cmd("BF.RESERVE " + BLOOM + " 0.01 " + names + "  → OK (1% target false-positive rate)");
        List<Object[]> adds = new ArrayList<>();
        for (int start = 0; start < names; start += 500) {
            List<Object> command = new ArrayList<>(List.of("BF.MADD", BLOOM));
            for (int i = start; i < Math.min(names, start + 500); i++) {
                command.add("user_" + i);
            }
            adds.add(command.toArray());
        }
        modules.pipeline(adds);
        demo.cmd("BF.MADD " + BLOOM + " user_0 … user_" + (names - 1) + "  (" + adds.size() + " pipelined batches)");

        int probes = 1000;
        long falseNegatives = probes - countHits(memberProbe("user_", probes));
        long falsePositives = countHits(memberProbe("guest_", probes));
        demo.cmd("BF.MEXISTS " + BLOOM + " user_0 … user_" + (probes - 1) + "  → all 1 (no false negatives)");
        demo.cmd("BF.MEXISTS " + BLOOM + " guest_0 … guest_" + (probes - 1) + "  → " + falsePositives + " false positives");
        long bloomBytes = memoryUsage(BLOOM);

        String setKey = "lab:bloom:usernames:exact-set";
        List<Object[]> sadds = new ArrayList<>();
        for (Object[] add : adds) {
            Object[] sadd = add.clone();
            sadd[0] = "SADD";
            sadd[1] = setKey;
            sadds.add(sadd);
        }
        modules.pipeline(sadds);
        long setBytes = memoryUsage(setKey);
        redis.unlink(setKey);
        demo.cmd("MEMORY USAGE " + BLOOM + "  → " + bloomBytes + " bytes; same names in a SET  → " + setBytes + " bytes");

        double fpRate = falsePositives * 100.0 / probes;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inserted", names);
        data.put("probes", probes);
        data.put("falsePositives", falsePositives);
        data.put("falsePositiveRatePct", fpRate);
        data.put("bloomBytes", bloomBytes);
        data.put("setBytes", setBytes);
        demo.check("No false negatives: every added name is found", falseNegatives == 0, probes + "/" + probes + " found");
        demo.check("False-positive rate near the 1% target", fpRate <= 3.0, String.format("%.1f%%", fpRate));
        demo.check("Bloom filter uses less memory than a SET", bloomBytes < setBytes, bloomBytes + " vs " + setBytes + " bytes");
        return demo.finish(data);
    }

    public DemoResult bloomCheck(String name) {
        Demo demo = Demo.start("bloom-check");
        String candidate = name == null ? "" : name.trim();
        if (!Boolean.TRUE.equals(redis.hasKey(BLOOM))) {
            bloom(5000);
            demo.cmd("(filter was missing, so it was rebuilt with user_0 … user_4999)");
        }
        Long seen = (Long) modules.scalar("BF.EXISTS", BLOOM, candidate);
        demo.cmd("BF.EXISTS " + BLOOM + " \"" + candidate + "\"  → " + seen);
        demo.check("Lookup answered", seen != null, seen == 1 ? "probably taken" : "definitely available");
        return demo.finish(Map.of("name", candidate, "maybeTaken", seen != null && seen == 1));
    }

    private long countHits(Object[] command) {
        long hits = 0;
        for (Object hit : modules.list((String) command[0], java.util.Arrays.copyOfRange(command, 1, command.length))) {
            if (Long.valueOf(1).equals(hit)) {
                hits++;
            }
        }
        return hits;
    }

    private static Object[] memberProbe(String prefix, int count) {
        Object[] command = new Object[count + 2];
        command[0] = "BF.MEXISTS";
        command[1] = BLOOM;
        for (int i = 0; i < count; i++) {
            command[i + 2] = prefix + i;
        }
        return command;
    }

    long memoryUsage(String key) {
        Object usage = modules.scalar("MEMORY", "USAGE", key);
        return usage instanceof Long bytes ? bytes : 0;
    }
}
