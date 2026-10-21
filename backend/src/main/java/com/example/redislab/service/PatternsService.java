package com.example.redislab.service;

import com.example.redislab.model.DemoResult;
import com.example.redislab.support.Demo;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Patterns built on Redis primitives: rate limiting, locks, transactions, Lua, pipelining, pub/sub. */
@Service
public class PatternsService {

    public static final int RATE_LIMIT = 5;
    public static final long RATE_WINDOW_MS = 10_000;

    /** Sliding-window log: drop timestamps older than the window, then admit only if under the limit. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final RedisScript<List> SLIDING_WINDOW = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)
            if count < limit then
              redis.call('ZADD', key, now, ARGV[4])
              redis.call('PEXPIRE', key, window)
              return {1, count + 1}
            end
            return {0, count}
            """, List.class);

    /** Only the owner (matching token) may release a lock: compare and delete in one atomic step. */
    private static final RedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> RESERVE_STOCK = new DefaultRedisScript<>("""
            local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
            if stock > 0 then
              redis.call('DECR', KEYS[1])
              redis.call('RPUSH', KEYS[2], ARGV[1])
              return 1
            end
            return 0
            """, Long.class);

    private static final String LOCK_KEY = "lab:lock:nightly-report";
    private static final String ALICE = "lab:tx:balance:alice";
    private static final String BOB = "lab:tx:balance:bob";

    private final StringRedisTemplate redis;
    private final EventStreamService events;

    public PatternsService(StringRedisTemplate redis, EventStreamService events) {
        this.redis = redis;
        this.events = events;
    }

    // ---- Rate limiting -----------------------------------------------------------------

    public DemoResult rateLimitHit(String client) {
        Demo demo = Demo.start("ratelimit-hit");
        Map<String, Object> hit = hit(demo, client);
        Map<String, Object> data = new LinkedHashMap<>(hit);
        data.put("window", window(client));
        demo.check("Script answered allow/deny atomically", hit.containsKey("allowed"), "allowed = " + hit.get("allowed"));
        return demo.finish(data);
    }

    public DemoResult rateLimitBurst(String client, int requests) {
        Demo demo = Demo.start("ratelimit-burst");
        redis.delete(rateKey(client));
        demo.cmd("DEL " + rateKey(client));
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            Map<String, Object> hit = hit(demo, client);
            hit.put("request", i + 1);
            results.add(hit);
        }
        long allowed = results.stream().filter(r -> Boolean.TRUE.equals(r.get("allowed"))).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("limit", RATE_LIMIT);
        data.put("windowMs", RATE_WINDOW_MS);
        data.put("results", results);
        data.put("allowed", allowed);
        data.put("blocked", requests - allowed);
        data.put("window", window(client));
        demo.check("Exactly " + Math.min(requests, RATE_LIMIT) + " requests were admitted",
                allowed == Math.min(requests, RATE_LIMIT), allowed + " allowed, " + (requests - allowed) + " blocked");
        demo.check("The first requests win, the rest are rejected", results.stream().limit(Math.min(requests, RATE_LIMIT))
                .allMatch(r -> Boolean.TRUE.equals(r.get("allowed"))), "no reordering");
        return demo.finish(data);
    }

    private Map<String, Object> hit(Demo demo, String client) {
        long now = System.currentTimeMillis();
        String member = now + "-" + UUID.randomUUID().toString().substring(0, 8);
        @SuppressWarnings("unchecked")
        List<Long> reply = redis.execute(SLIDING_WINDOW, List.of(rateKey(client)),
                String.valueOf(now), String.valueOf(RATE_WINDOW_MS), String.valueOf(RATE_LIMIT), member);
        boolean allowed = reply.get(0) == 1L;
        long used = reply.get(1);
        demo.cmd("EVALSHA sliding_window 1 " + rateKey(client) + " " + now + " " + RATE_WINDOW_MS + " " + RATE_LIMIT
                + "  → " + (allowed ? "ALLOW" : "DENY") + " (" + used + "/" + RATE_LIMIT + ")");
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("allowed", allowed);
        hit.put("used", used);
        hit.put("remaining", Math.max(0, RATE_LIMIT - used));
        hit.put("limit", RATE_LIMIT);
        hit.put("windowMs", RATE_WINDOW_MS);
        hit.put("at", now);
        return hit;
    }

    private List<Long> window(String client) {
        Set<ZSetOperations.TypedTuple<String>> entries = redis.opsForZSet().rangeWithScores(rateKey(client), 0, -1);
        List<Long> timestamps = new ArrayList<>();
        if (entries != null) {
            entries.forEach(e -> timestamps.add(Objects.requireNonNull(e.getScore()).longValue()));
        }
        return timestamps;
    }

    private static String rateKey(String client) {
        return "lab:ratelimit:" + client;
    }

    // ---- Distributed lock --------------------------------------------------------------

    /** Workers compete for one lock with SET NX PX; the winner works, then releases with a Lua check. */
    public DemoResult lock(int workers) throws InterruptedException {
        Demo demo = Demo.start("lock");
        redis.delete(LOCK_KEY);
        AtomicInteger holders = new AtomicInteger();
        AtomicInteger maxHolders = new AtomicInteger();
        List<Map<String, Object>> timeline = Collections.synchronizedList(new ArrayList<>());
        long begin = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch done = new CountDownLatch(workers);
        for (int w = 1; w <= workers; w++) {
            String worker = "worker-" + w;
            pool.submit(() -> {
                try {
                    String token = UUID.randomUUID().toString();
                    int attempts = 0;
                    long deadline = System.currentTimeMillis() + 15_000;
                    while (System.currentTimeMillis() < deadline) {
                        attempts++;
                        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, token, Duration.ofSeconds(5)))) {
                            long acquired = System.currentTimeMillis() - begin;
                            maxHolders.accumulateAndGet(holders.incrementAndGet(), Math::max);
                            Thread.sleep(150);
                            holders.decrementAndGet();
                            long released = System.currentTimeMillis() - begin;
                            Long deleted = redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), token);
                            timeline.add(Map.of("worker", worker, "attempts", attempts, "acquiredMs", acquired,
                                    "releasedMs", released, "released", Objects.equals(deleted, 1L)));
                            return;
                        }
                        Thread.sleep(20);
                    }
                    timeline.add(Map.of("worker", worker, "attempts", attempts, "timedOut", true));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        timeline.sort((a, b) -> Long.compare(((Number) a.getOrDefault("acquiredMs", Long.MAX_VALUE)).longValue(),
                ((Number) b.getOrDefault("acquiredMs", Long.MAX_VALUE)).longValue()));
        int totalAttempts = timeline.stream().mapToInt(t -> (int) t.get("attempts")).sum();
        demo.cmd(workers + " workers loop: SET " + LOCK_KEY + " <uuid> NX PX 5000  (" + totalAttempts + " attempts in total)");
        for (Map<String, Object> t : timeline) {
            demo.cmd(t.get("worker") + ": SET NX → OK after " + t.get("attempts") + " tries, worked 150 ms, EVALSHA release → 1");
        }

        // Safety: a client that does not own the lock cannot release it
        redis.opsForValue().set(LOCK_KEY, "owner-token", Duration.ofSeconds(5));
        Long wrongRelease = redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), "intruder-token");
        Long rightRelease = redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), "owner-token");
        demo.cmd("EVALSHA release 1 " + LOCK_KEY + " intruder-token  → " + wrongRelease);
        demo.cmd("EVALSHA release 1 " + LOCK_KEY + " owner-token  → " + rightRelease);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workers", workers);
        data.put("timeline", timeline);
        data.put("maxConcurrentHolders", maxHolders.get());
        data.put("wrongTokenRelease", wrongRelease);
        demo.check("Never more than one worker inside the critical section", maxHolders.get() == 1,
                "max concurrent holders = " + maxHolders.get());
        demo.check("Every worker eventually got the lock", timeline.size() == workers
                && timeline.stream().noneMatch(t -> t.containsKey("timedOut")), timeline.size() + "/" + workers);
        demo.check("A non-owner cannot release the lock", Objects.equals(wrongRelease, 0L) && Objects.equals(rightRelease, 1L),
                "intruder → 0, owner → 1");
        return demo.finish(data);
    }

    // ---- Transactions ------------------------------------------------------------------

    public DemoResult transfer(int amount) {
        Demo demo = Demo.start("transactions");
        resetBalancesIfNeeded(demo, amount);
        List<Object> results = redis.execute(new SessionCallback<>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public List<Object> execute(RedisOperations operations) throws DataAccessException {
                operations.multi();
                operations.opsForValue().decrement(ALICE, amount);
                operations.opsForValue().increment(BOB, amount);
                return operations.exec();
            }
        });
        demo.cmd("MULTI").cmd("DECRBY " + ALICE + " " + amount + "  → QUEUED").cmd("INCRBY " + BOB + " " + amount + "  → QUEUED");
        demo.cmd("EXEC  → " + results);
        Map<String, Object> data = balances(demo);
        data.put("execResults", results);
        demo.check("Both commands ran as one unit", results != null && results.size() == 2, String.valueOf(results));
        demo.check("Money was moved, not created", ((long) data.get("alice")) + ((long) data.get("bob")) == 150,
                "alice + bob = " + (((long) data.get("alice")) + ((long) data.get("bob"))));
        return demo.finish(data);
    }

    /** WATCH gives optimistic locking: if another client changes a watched key, EXEC aborts. */
    public DemoResult watchConflict() {
        Demo demo = Demo.start("transactions-watch");
        redis.opsForValue().set(ALICE, "100");
        redis.opsForValue().set(BOB, "50");
        demo.cmd("SET " + ALICE + " 100; SET " + BOB + " 50");
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            boolean interfere = attempt == 1;
            List<Object> results = redis.execute(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public List<Object> execute(RedisOperations operations) throws DataAccessException {
                    operations.watch(ALICE);
                    long balance = Long.parseLong((String) Objects.requireNonNull(operations.opsForValue().get(ALICE)));
                    if (interfere) {
                        // Another client deposits between our read and our write
                        CompletableFuture.runAsync(() -> redis.opsForValue().increment(ALICE, 10)).join();
                    }
                    operations.multi();
                    operations.opsForValue().set(ALICE, String.valueOf(balance - 30));
                    operations.opsForValue().increment(BOB, 30);
                    return operations.exec();
                }
            });
            boolean committed = results != null && !results.isEmpty();
            demo.cmd("WATCH " + ALICE + "; GET " + ALICE);
            if (interfere) {
                demo.cmd("   (other client) INCRBY " + ALICE + " 10");
            }
            demo.cmd("MULTI; SET " + ALICE + " <balance-30>; INCRBY " + BOB + " 30; EXEC  → "
                    + (committed ? results : "(nil) aborted"));
            attempts.add(Map.of("attempt", attempt, "interfered", interfere, "committed", committed));
            if (committed) {
                break;
            }
        }
        Map<String, Object> data = balances(demo);
        data.put("attempts", attempts);
        long total = ((long) data.get("alice")) + ((long) data.get("bob"));
        demo.check("The first EXEC aborted because a watched key changed",
                !attempts.isEmpty() && !(boolean) attempts.get(0).get("committed"), "EXEC returned nil");
        demo.check("The retry committed", attempts.size() == 2 && (boolean) attempts.get(1).get("committed"), attempts.size() + " attempts");
        demo.check("The concurrent deposit was not lost", total == 160, "alice + bob = " + total + " (150 + 10 deposit)");
        return demo.finish(data);
    }

    private void resetBalancesIfNeeded(Demo demo, int amount) {
        String alice = redis.opsForValue().get(ALICE);
        String bob = redis.opsForValue().get(BOB);
        if (alice == null || bob == null || Long.parseLong(alice) < amount
                || Long.parseLong(alice) + Long.parseLong(bob) != 150) {
            redis.opsForValue().set(ALICE, "100");
            redis.opsForValue().set(BOB, "50");
            demo.cmd("SET " + ALICE + " 100; SET " + BOB + " 50  (reset balances)");
        }
    }

    private Map<String, Object> balances(Demo demo) {
        long alice = Long.parseLong(Objects.requireNonNull(redis.opsForValue().get(ALICE)));
        long bob = Long.parseLong(Objects.requireNonNull(redis.opsForValue().get(BOB)));
        demo.cmd("MGET " + ALICE + " " + BOB + "  → [" + alice + ", " + bob + "]");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alice", alice);
        data.put("bob", bob);
        return data;
    }

    // ---- Lua scripting -----------------------------------------------------------------

    /** A flash sale: many buyers race for limited stock, once with a Lua script and once with GET + SET. */
    public DemoResult flashSale(int stock, int buyers) throws InterruptedException {
        Demo demo = Demo.start("lua");
        String atomicStock = "lab:lua:stock:atomic";
        String atomicOrders = "lab:lua:orders:atomic";
        String naiveStock = "lab:lua:stock:naive";
        String naiveOrders = "lab:lua:orders:naive";
        redis.delete(List.of(atomicOrders, naiveOrders));
        redis.opsForValue().set(atomicStock, String.valueOf(stock));
        redis.opsForValue().set(naiveStock, String.valueOf(stock));
        demo.cmd("SET " + atomicStock + " " + stock + "; SET " + naiveStock + " " + stock);

        AtomicInteger atomicSold = new AtomicInteger();
        runBuyers(buyers, buyer -> {
            Long ok = redis.execute(RESERVE_STOCK, List.of(atomicStock, atomicOrders), buyer);
            if (Objects.equals(ok, 1L)) {
                atomicSold.incrementAndGet();
            }
        });
        demo.cmd(buyers + " buyers × EVALSHA reserve_stock 2 " + atomicStock + " " + atomicOrders + " <buyer>");

        AtomicInteger naiveSold = new AtomicInteger();
        runBuyers(buyers, buyer -> {
            long left = Long.parseLong(Objects.requireNonNull(redis.opsForValue().get(naiveStock)));
            if (left > 0) {
                sleepQuietly(2);
                redis.opsForValue().set(naiveStock, String.valueOf(left - 1));
                redis.opsForList().rightPush(naiveOrders, buyer);
                naiveSold.incrementAndGet();
            }
        });
        demo.cmd(buyers + " buyers × (GET " + naiveStock + "; if > 0: SET " + naiveStock + " n-1; RPUSH " + naiveOrders + ")");

        long atomicLeft = Long.parseLong(Objects.requireNonNull(redis.opsForValue().get(atomicStock)));
        Long atomicOrderCount = redis.opsForList().size(atomicOrders);
        Long naiveOrderCount = redis.opsForList().size(naiveOrders);
        demo.cmd("GET " + atomicStock + "  → " + atomicLeft + "; LLEN " + atomicOrders + "  → " + atomicOrderCount);
        demo.cmd("LLEN " + naiveOrders + "  → " + naiveOrderCount);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stock", stock);
        data.put("buyers", buyers);
        data.put("atomicSold", atomicSold.get());
        data.put("atomicStockLeft", atomicLeft);
        data.put("naiveSold", naiveSold.get());
        data.put("oversold", Math.max(0, naiveSold.get() - stock));
        data.put("winners", redis.opsForList().range(atomicOrders, 0, 9));
        demo.check("Lua sold exactly the available stock", atomicSold.get() == Math.min(stock, buyers),
                atomicSold.get() + " sold of " + stock);
        demo.check("Stock never went negative", atomicLeft >= 0, "left = " + atomicLeft);
        demo.check("Every sale has exactly one order", Objects.equals(atomicOrderCount, (long) atomicSold.get()),
                atomicOrderCount + " orders");
        return demo.finish(data);
    }

    private static void runBuyers(int buyers, java.util.function.Consumer<String> buy) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(buyers, 32));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(buyers);
        for (int i = 1; i <= buyers; i++) {
            String buyer = "buyer-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    buy.accept(buyer);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- Pipelining --------------------------------------------------------------------

    public DemoResult pipeline(int commands) {
        Demo demo = Demo.start("pipeline");
        String sequentialKey = "lab:pipe:sequential";
        String pipelinedKey = "lab:pipe:pipelined";
        redis.delete(List.of(sequentialKey, pipelinedKey));

        long start = System.nanoTime();
        for (int i = 0; i < commands; i++) {
            redis.opsForValue().increment(sequentialKey);
        }
        double sequentialMs = Demo.elapsedMs(start);
        demo.cmd(commands + " × INCR " + sequentialKey + "  (send, wait for reply, repeat)");

        start = System.nanoTime();
        byte[] rawKey = pipelinedKey.getBytes(StandardCharsets.UTF_8);
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for (int i = 0; i < commands; i++) {
                connection.stringCommands().incr(rawKey);
            }
            return null;
        });
        double pipelinedMs = Demo.elapsedMs(start);
        demo.cmd(commands + " × INCR " + pipelinedKey + "  (all sent in one batch, replies read together)");

        long sequential = Long.parseLong(Objects.requireNonNull(redis.opsForValue().get(sequentialKey)));
        long pipelined = Long.parseLong(Objects.requireNonNull(redis.opsForValue().get(pipelinedKey)));
        demo.cmd("MGET " + sequentialKey + " " + pipelinedKey + "  → [" + sequential + ", " + pipelined + "]");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commands", commands);
        data.put("sequentialMs", sequentialMs);
        data.put("pipelinedMs", pipelinedMs);
        data.put("speedup", pipelinedMs == 0 ? null : Math.round(sequentialMs / pipelinedMs * 10) / 10.0);
        demo.check("Both approaches applied every command", sequential == commands && pipelined == commands,
                sequential + " and " + pipelined);
        demo.check("Pipelining was faster", pipelinedMs < sequentialMs,
                String.format("%.1f ms vs %.1f ms", pipelinedMs, sequentialMs));
        return demo.finish(data);
    }

    // ---- Pub/Sub -----------------------------------------------------------------------

    public DemoResult publish(String channel, String message) throws InterruptedException {
        Demo demo = Demo.start("pubsub");
        String safeChannel = channel != null && channel.matches("lab:[A-Za-z0-9:_-]{1,40}") ? channel : "lab:chat";
        String payload = message == null || message.isBlank() ? "hello" : message.substring(0, Math.min(200, message.length()));
        CountDownLatch received = events.expect(safeChannel, payload);
        long start = System.nanoTime();
        Long receivers = redis.execute((RedisCallback<Long>) connection -> connection.publish(
                safeChannel.getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8)));
        boolean delivered = received.await(2, TimeUnit.SECONDS);
        double roundTripMs = Demo.elapsedMs(start);
        events.forget(safeChannel, payload);
        demo.cmd("PUBLISH " + safeChannel + " \"" + payload + "\"  → " + receivers + " subscriber(s)");
        demo.cmd("(subscriber on PSUBSCRIBE lab:*) message → " + safeChannel);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel", safeChannel);
        data.put("message", payload);
        data.put("receivers", receivers);
        data.put("delivered", delivered);
        data.put("roundTripMs", roundTripMs);
        data.put("browsers", events.subscriberCount());
        demo.check("At least one subscriber was listening", receivers != null && receivers > 0, receivers + " receiver(s)");
        demo.check("The subscriber received the message", delivered, delivered ? roundTripMs + " ms round trip" : "timed out");
        return demo.finish(data);
    }
}
