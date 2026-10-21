package com.example.redislab.service;

import com.example.redislab.model.DemoResult;
import com.example.redislab.support.Demo;
import com.example.redislab.support.RedisModules;
import org.springframework.data.redis.connection.RedisListCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** The core data types: strings, counters, expiry, hashes, lists, sets and sorted sets. */
@Service
public class DataStructuresService {

    static final List<String> PLAYERS = List.of("Ada", "Linus", "Grace", "Alan", "Margaret",
            "Ken", "Barbara", "Dennis", "Radia", "Tim");

    private static final String LEADERBOARD = "lab:zset:leaderboard";
    private static final String JOBS = "lab:list:jobs";
    private static final String PROCESSING = "lab:list:processing";
    private static final String DONE = "lab:list:done";
    private static final String SESSION = "lab:hash:session:demo";
    private static final List<String> TTL_KEYS = List.of("lab:ttl:otp", "lab:ttl:session", "lab:ttl:persisted");

    private final StringRedisTemplate redis;
    private final RedisModules modules;

    public DataStructuresService(StringRedisTemplate redis, RedisModules modules) {
        this.redis = redis;
        this.modules = modules;
    }

    // ---- Strings -------------------------------------------------------------------------

    public DemoResult strings() {
        Demo demo = Demo.start("strings");
        var ops = redis.opsForValue();
        redis.delete(List.of("lab:str:greeting", "lab:str:once", "lab:str:price"));

        ops.set("lab:str:greeting", "Hello");
        demo.cmd("SET lab:str:greeting \"Hello\"  → OK");
        Integer length = ops.append("lab:str:greeting", ", Redis!");
        demo.cmd("APPEND lab:str:greeting \", Redis!\"  → " + length);
        String greeting = ops.get("lab:str:greeting");
        demo.cmd("GET lab:str:greeting  → \"" + greeting + "\"");
        String slice = ops.get("lab:str:greeting", 0, 4);
        demo.cmd("GETRANGE lab:str:greeting 0 4  → \"" + slice + "\"");

        ops.multiSet(Map.of("lab:str:color", "teal", "lab:str:size", "XL"));
        demo.cmd("MSET lab:str:color teal lab:str:size XL  → OK");
        List<String> many = ops.multiGet(List.of("lab:str:color", "lab:str:size", "lab:str:missing"));
        demo.cmd("MGET lab:str:color lab:str:size lab:str:missing  → " + many);

        Boolean first = ops.setIfAbsent("lab:str:once", "first-writer");
        Boolean second = ops.setIfAbsent("lab:str:once", "second-writer");
        demo.cmd("SET lab:str:once first-writer NX  → " + (Boolean.TRUE.equals(first) ? "OK" : "(nil)"));
        demo.cmd("SET lab:str:once second-writer NX  → " + (Boolean.TRUE.equals(second) ? "OK" : "(nil)"));

        ops.set("lab:str:price", "19.99");
        Double price = ops.increment("lab:str:price", 0.5);
        demo.cmd("INCRBYFLOAT lab:str:price 0.5  → " + price);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("greeting", greeting);
        data.put("slice", slice);
        data.put("mget", many);
        data.put("setnxFirst", first);
        data.put("setnxSecond", second);
        data.put("once", ops.get("lab:str:once"));
        data.put("price", price);
        demo.check("APPEND returned the new length", Objects.equals(length, 13), "length " + length);
        demo.check("MGET returns nil for a missing key", many != null && many.get(2) == null, String.valueOf(many));
        demo.check("SET NX only lets the first writer in", Boolean.TRUE.equals(first) && Boolean.FALSE.equals(second),
                "value stayed \"" + data.get("once") + "\"");
        demo.check("INCRBYFLOAT did decimal math server-side", price != null && Math.abs(price - 20.49) < 1e-9, String.valueOf(price));
        return demo.finish(data);
    }

    /** Many threads increment two counters: one with atomic INCR, one with read-modify-write GET + SET. */
    public DemoResult counter(int threads, int incrementsPerThread) throws InterruptedException {
        Demo demo = Demo.start("counter");
        String atomic = "lab:str:counter:atomic";
        String naive = "lab:str:counter:naive";
        redis.opsForValue().set(atomic, "0");
        redis.opsForValue().set(naive, "0");
        demo.cmd("SET " + atomic + " 0").cmd("SET " + naive + " 0");

        long atomicMs = race(threads, incrementsPerThread, () -> redis.opsForValue().increment(atomic));
        demo.cmd(threads + " threads × " + incrementsPerThread + " × INCR " + atomic);
        long naiveMs = race(threads, incrementsPerThread, () -> {
            long value = Long.parseLong(Objects.requireNonNull(redis.opsForValue().get(naive)));
            redis.opsForValue().set(naive, String.valueOf(value + 1));
        });
        demo.cmd(threads + " threads × " + incrementsPerThread + " × (GET " + naive + " … SET " + naive + " n+1)");

        long expected = (long) threads * incrementsPerThread;
        long atomicValue = Long.parseLong(Objects.requireNonNull(redis.opsForValue().get(atomic)));
        long naiveValue = Long.parseLong(Objects.requireNonNull(redis.opsForValue().get(naive)));
        demo.cmd("GET " + atomic + "  → " + atomicValue).cmd("GET " + naive + "  → " + naiveValue);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("threads", threads);
        data.put("expected", expected);
        data.put("atomic", atomicValue);
        data.put("naive", naiveValue);
        data.put("lostUpdates", expected - naiveValue);
        data.put("atomicMs", atomicMs);
        data.put("naiveMs", naiveMs);
        demo.check("INCR never lost an update", atomicValue == expected, atomicValue + " / " + expected);
        return demo.finish(data);
    }

    private static long race(int threads, int iterations, Runnable action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        action.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long begin = System.currentTimeMillis();
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        return System.currentTimeMillis() - begin;
    }

    // ---- Expiry (TTL) --------------------------------------------------------------------

    public DemoResult ttl(int seconds) {
        Demo demo = Demo.start("ttl");
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        redis.opsForValue().set("lab:ttl:otp", code, seconds, TimeUnit.SECONDS);
        demo.cmd("SET lab:ttl:otp " + code + " EX " + seconds);
        redis.opsForValue().set("lab:ttl:session", "active", seconds * 2L, TimeUnit.SECONDS);
        demo.cmd("SET lab:ttl:session active EX " + seconds * 2);
        redis.opsForValue().set("lab:ttl:persisted", "keep-me", seconds, TimeUnit.SECONDS);
        redis.persist("lab:ttl:persisted");
        demo.cmd("SET lab:ttl:persisted keep-me EX " + seconds).cmd("PERSIST lab:ttl:persisted  → 1");

        List<Map<String, Object>> keys = ttlStatus(demo);
        long otpTtl = (long) keys.get(0).get("ttlMs");
        demo.check("OTP key counts down from " + seconds + "s", otpTtl > 0 && otpTtl <= seconds * 1000L, otpTtl + " ms left");
        demo.check("PERSIST removed the expiry", (long) keys.get(2).get("ttlMs") == -1, "PTTL -1 means no expiry");
        return demo.finish(Map.of("seconds", seconds, "keys", keys));
    }

    public DemoResult ttlStatus() {
        Demo demo = Demo.start("ttl-status");
        return demo.finish(Map.of("keys", ttlStatus(demo)));
    }

    private List<Map<String, Object>> ttlStatus(Demo demo) {
        List<Map<String, Object>> keys = new ArrayList<>();
        for (String key : TTL_KEYS) {
            Long pttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
            String value = redis.opsForValue().get(key);
            demo.cmd("PTTL " + key + "  → " + pttl);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);
            entry.put("value", value);
            entry.put("ttlMs", pttl == null ? -2L : pttl);
            entry.put("exists", value != null);
            keys.add(entry);
        }
        return keys;
    }

    // ---- Hashes ------------------------------------------------------------------------

    public DemoResult hashes() {
        Demo demo = Demo.start("hashes");
        redis.delete(SESSION);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("user", "alice");
        fields.put("role", "admin");
        fields.put("cart_items", "0");
        fields.put("theme", "dark");
        fields.put("csrf_token", Long.toHexString(ThreadLocalRandom.current().nextLong()));
        redis.opsForHash().putAll(SESSION, fields);
        demo.cmd("HSET " + SESSION + " user alice role admin cart_items 0 theme dark csrf_token …  → 5");
        Long cart = redis.opsForHash().increment(SESSION, "cart_items", 3);
        demo.cmd("HINCRBY " + SESSION + " cart_items 3  → " + cart);
        Object role = redis.opsForHash().get(SESSION, "role");
        demo.cmd("HGET " + SESSION + " role  → \"" + role + "\"");
        Long removed = redis.opsForHash().delete(SESSION, "theme");
        demo.cmd("HDEL " + SESSION + " theme  → " + removed);
        List<Object> expire = modules.list("HEXPIRE", SESSION, 8, "FIELDS", 1, "csrf_token");
        demo.cmd("HEXPIRE " + SESSION + " 8 FIELDS 1 csrf_token  → " + expire);
        redis.expire(SESSION, 30, TimeUnit.MINUTES);
        demo.cmd("EXPIRE " + SESSION + " 1800");

        Map<String, Object> state = hashState(demo);
        @SuppressWarnings("unchecked")
        Map<Object, Object> all = (Map<Object, Object>) state.get("fields");
        demo.check("HINCRBY updated one field atomically", Objects.equals(cart, 3L), "cart_items = " + cart);
        demo.check("HDEL removed the theme field", !all.containsKey("theme"), all.keySet().toString());
        demo.check("csrf_token has its own field-level TTL", ((Number) state.get("csrfTtlMs")).longValue() > 0,
                state.get("csrfTtlMs") + " ms");
        return demo.finish(state);
    }

    public DemoResult hashStatus() {
        Demo demo = Demo.start("hashes-status");
        return demo.finish(hashState(demo));
    }

    private Map<String, Object> hashState(Demo demo) {
        Map<Object, Object> all = redis.opsForHash().entries(SESSION);
        demo.cmd("HGETALL " + SESSION + "  → " + all.size() + " fields");
        List<Object> ttl = modules.list("HPTTL", SESSION, "FIELDS", 1, "csrf_token");
        demo.cmd("HPTTL " + SESSION + " FIELDS 1 csrf_token  → " + ttl);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", SESSION);
        data.put("fields", all);
        data.put("csrfTtlMs", ttl.isEmpty() ? -2L : ttl.get(0));
        return data;
    }

    // ---- Lists (reliable queue) --------------------------------------------------------

    public DemoResult enqueue(int count) {
        Demo demo = Demo.start("lists-enqueue");
        Long before = redis.opsForList().size(JOBS);
        List<String> jobs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Long seq = redis.opsForValue().increment("lab:list:seq");
            jobs.add("job-" + seq);
        }
        Long after = redis.opsForList().leftPushAll(JOBS, jobs);
        demo.cmd("INCR lab:list:seq  ×" + count);
        demo.cmd("LPUSH " + JOBS + " " + String.join(" ", jobs) + "  → " + after);
        Map<String, Object> data = listState(demo);
        data.put("pushed", jobs);
        demo.check("LPUSH grew the queue by " + count, after != null && after - (before == null ? 0 : before) == count,
                before + " → " + after);
        return demo.finish(data);
    }

    /** LMOVE each job to a processing list, then remove it once "done" (the reliable-queue pattern). */
    public DemoResult work(int count) {
        Demo demo = Demo.start("lists-work");
        List<String> processed = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String job = redis.opsForList().move(JOBS, RedisListCommands.Direction.RIGHT,
                    PROCESSING, RedisListCommands.Direction.LEFT);
            if (job == null) {
                demo.cmd("LMOVE " + JOBS + " " + PROCESSING + " RIGHT LEFT  → (nil), queue empty");
                break;
            }
            demo.cmd("LMOVE " + JOBS + " " + PROCESSING + " RIGHT LEFT  → " + job);
            redis.opsForList().remove(PROCESSING, 1, job);
            redis.opsForList().leftPush(DONE, job);
            redis.opsForList().trim(DONE, 0, 9);
            demo.cmd("LREM " + PROCESSING + " 1 " + job + "; LPUSH " + DONE + " " + job + "; LTRIM " + DONE + " 0 9");
            processed.add(job);
        }
        Map<String, Object> data = listState(demo);
        data.put("processed", processed);
        demo.check("Jobs leave in FIFO order (oldest first)", isAscending(processed), String.valueOf(processed));
        demo.check("Nothing is stuck in the processing list", ((List<?>) data.get("processing")).isEmpty(), "LLEN processing = 0");
        demo.check("The done list is capped at 10 by LTRIM", ((List<?>) data.get("done")).size() <= 10,
                ((List<?>) data.get("done")).size() + " entries");
        return demo.finish(data);
    }

    private Map<String, Object> listState(Demo demo) {
        List<String> pending = redis.opsForList().range(JOBS, 0, 49);
        List<String> processing = redis.opsForList().range(PROCESSING, 0, -1);
        List<String> done = redis.opsForList().range(DONE, 0, -1);
        Long length = redis.opsForList().size(JOBS);
        demo.cmd("LRANGE " + JOBS + " 0 49; LRANGE " + PROCESSING + " 0 -1; LRANGE " + DONE + " 0 -1; LLEN " + JOBS + " → " + length);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pending", pending);
        data.put("pendingCount", length);
        data.put("processing", processing);
        data.put("done", done);
        return data;
    }

    private static boolean isAscending(List<String> jobs) {
        for (int i = 1; i < jobs.size(); i++) {
            if (Long.parseLong(jobs.get(i).substring(4)) < Long.parseLong(jobs.get(i - 1).substring(4))) {
                return false;
            }
        }
        return true;
    }

    // ---- Sets --------------------------------------------------------------------------

    public DemoResult sets() {
        Demo demo = Demo.start("sets");
        String alice = "lab:set:skills:alice";
        String bob = "lab:set:skills:bob";
        String visitors = "lab:set:visitors:today";
        redis.delete(List.of(alice, bob, visitors));
        redis.opsForSet().add(alice, "java", "spring", "redis", "postgres", "docker");
        redis.opsForSet().add(bob, "python", "redis", "docker", "kubernetes", "react");
        demo.cmd("SADD " + alice + " java spring redis postgres docker  → 5");
        demo.cmd("SADD " + bob + " python redis docker kubernetes react  → 5");
        Set<String> both = redis.opsForSet().intersect(alice, bob);
        demo.cmd("SINTER " + alice + " " + bob + "  → " + both);
        Set<String> either = redis.opsForSet().union(alice, bob);
        demo.cmd("SUNION " + alice + " " + bob + "  → " + either.size() + " members");
        Set<String> onlyAlice = redis.opsForSet().difference(alice, bob);
        demo.cmd("SDIFF " + alice + " " + bob + "  → " + onlyAlice);
        Set<String> onlyBob = redis.opsForSet().difference(bob, alice);
        demo.cmd("SDIFF " + bob + " " + alice + "  → " + onlyBob);
        Boolean knowsRedis = redis.opsForSet().isMember(alice, "redis");
        demo.cmd("SISMEMBER " + alice + " redis  → " + (Boolean.TRUE.equals(knowsRedis) ? 1 : 0));

        int pageViews = 1000;
        String[] ids = new String[pageViews];
        Set<String> exact = new java.util.HashSet<>();
        for (int i = 0; i < pageViews; i++) {
            ids[i] = "visitor-" + ThreadLocalRandom.current().nextInt(300);
            exact.add(ids[i]);
        }
        Long added = redis.opsForSet().add(visitors, ids);
        Long unique = redis.opsForSet().size(visitors);
        demo.cmd("SADD " + visitors + " … " + pageViews + " page views  → " + added + " new");
        demo.cmd("SCARD " + visitors + "  → " + unique);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alice", redis.opsForSet().members(alice));
        data.put("bob", redis.opsForSet().members(bob));
        data.put("intersection", both);
        data.put("union", either);
        data.put("onlyAlice", onlyAlice);
        data.put("onlyBob", onlyBob);
        data.put("pageViews", pageViews);
        data.put("uniqueVisitors", unique);
        demo.check("SINTER found the shared skills", Set.of("redis", "docker").equals(both), String.valueOf(both));
        demo.check("SUNION has no duplicates", either.size() == 8, either.size() + " members");
        demo.check("SCARD counts each visitor once", Objects.equals(unique, (long) exact.size()),
                unique + " unique out of " + pageViews + " views");
        return demo.finish(data);
    }

    // ---- Sorted sets (leaderboard) -----------------------------------------------------

    public DemoResult leaderboard(int rounds) {
        Demo demo = Demo.start("leaderboard");
        List<Map<String, Object>> plays = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            String player = PLAYERS.get(ThreadLocalRandom.current().nextInt(PLAYERS.size()));
            int points = 10 * ThreadLocalRandom.current().nextInt(1, 11);
            Double score = redis.opsForZSet().incrementScore(LEADERBOARD, player, points);
            demo.cmd("ZINCRBY " + LEADERBOARD + " " + points + " " + player + "  → " + score.longValue());
            plays.add(Map.of("player", player, "points", points, "score", score));
        }
        Map<String, Object> data = leaderboardState(demo);
        data.put("plays", plays);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) data.get("top");
        boolean sorted = true;
        for (int i = 1; i < top.size(); i++) {
            sorted &= ((Number) top.get(i - 1).get("score")).doubleValue() >= ((Number) top.get(i).get("score")).doubleValue();
        }
        demo.check("ZREVRANGE returns players highest score first", sorted, top.size() + " players ranked");
        demo.check("Each player appears once, however many times they scored",
                top.stream().map(e -> e.get("player")).distinct().count() == top.size(), "members are unique");
        return demo.finish(data);
    }

    public DemoResult resetLeaderboard() {
        Demo demo = Demo.start("leaderboard-reset");
        redis.delete(LEADERBOARD);
        demo.cmd("DEL " + LEADERBOARD);
        return demo.finish(leaderboardState(demo));
    }

    private Map<String, Object> leaderboardState(Demo demo) {
        Set<ZSetOperations.TypedTuple<String>> top = redis.opsForZSet().reverseRangeWithScores(LEADERBOARD, 0, 9);
        demo.cmd("ZREVRANGE " + LEADERBOARD + " 0 9 WITHSCORES");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (top != null) {
            int rank = 1;
            for (ZSetOperations.TypedTuple<String> tuple : top) {
                rows.add(Map.of("rank", rank++, "player", Objects.requireNonNull(tuple.getValue()),
                        "score", Objects.requireNonNullElse(tuple.getScore(), 0.0)));
            }
        }
        Long adaRank = redis.opsForZSet().reverseRank(LEADERBOARD, "Ada");
        Long over500 = redis.opsForZSet().count(LEADERBOARD, 500, Double.POSITIVE_INFINITY);
        demo.cmd("ZREVRANK " + LEADERBOARD + " Ada  → " + (adaRank == null ? "(nil)" : adaRank));
        demo.cmd("ZCOUNT " + LEADERBOARD + " 500 +inf  → " + over500);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("top", rows);
        data.put("adaRank", adaRank == null ? null : adaRank + 1);
        data.put("over500", over500);
        return data;
    }
}
