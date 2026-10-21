package com.example.redislab.service;

import com.example.redislab.model.DemoResult;
import com.example.redislab.support.Demo;
import com.example.redislab.support.RedisModules;
import io.lettuce.core.RedisCommandExecutionException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Redis Streams: an append-only log with consumer groups, acknowledgements and recovery. */
@Service
public class StreamsService {

    public static final String STREAM = "lab:stream:orders";
    public static final String GROUP = "fulfillment";
    private static final List<String> ITEMS = List.of("keyboard", "monitor", "mouse", "laptop", "headset", "webcam");

    private final StringRedisTemplate redis;
    private final RedisModules modules;

    public StreamsService(StringRedisTemplate redis, RedisModules modules) {
        this.redis = redis;
        this.modules = modules;
    }

    public DemoResult produce(int count) {
        Demo demo = Demo.start("streams-produce");
        ensureGroup(demo);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Long orderNo = redis.opsForValue().increment("lab:stream:seq");
            String item = ITEMS.get(ThreadLocalRandom.current().nextInt(ITEMS.size()));
            int qty = ThreadLocalRandom.current().nextInt(1, 5);
            RecordId id = redis.opsForStream().add(StreamRecords.string(Map.of(
                    "order", "order-" + orderNo, "item", item, "qty", String.valueOf(qty))).withStreamKey(STREAM));
            demo.cmd("XADD " + STREAM + " * order order-" + orderNo + " item " + item + " qty " + qty + "  → " + id);
            ids.add(String.valueOf(id));
        }
        Map<String, Object> data = state(demo);
        data.put("added", ids);
        demo.check("XADD assigned increasing IDs", isIncreasing(ids), ids.isEmpty() ? "-" : ids.get(0) + " … " + ids.get(ids.size() - 1));
        return demo.finish(data);
    }

    public DemoResult consume(String consumer, int count, boolean ack) {
        Demo demo = Demo.start("streams-consume");
        ensureGroup(demo);
        String name = consumer.matches("[A-Za-z0-9_-]{1,20}") ? consumer : "worker-a";
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(Consumer.from(GROUP, name),
                StreamReadOptions.empty().count(count), StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        demo.cmd("XREADGROUP GROUP " + GROUP + " " + name + " COUNT " + count + " STREAMS " + STREAM + " >  → "
                + (records == null ? 0 : records.size()) + " entries");
        List<Map<String, Object>> delivered = toEntries(records);
        if (ack && records != null && !records.isEmpty()) {
            RecordId[] ids = records.stream().map(MapRecord::getId).toArray(RecordId[]::new);
            Long acked = redis.opsForStream().acknowledge(STREAM, GROUP, ids);
            demo.cmd("XACK " + STREAM + " " + GROUP + " " + ids.length + " ids  → " + acked);
        }
        Map<String, Object> data = state(demo);
        data.put("consumer", name);
        data.put("delivered", delivered);
        data.put("acked", ack);
        demo.check("The group handed out only new entries", delivered.stream().map(e -> e.get("id")).distinct().count() == delivered.size(),
                delivered.size() + " entries to " + name);
        return demo.finish(data);
    }

    /** XAUTOCLAIM moves entries another consumer read but never acknowledged (e.g. it crashed). */
    public DemoResult recover(String consumer) {
        Demo demo = Demo.start("streams-recover");
        ensureGroup(demo);
        String name = consumer.matches("[A-Za-z0-9_-]{1,20}") ? consumer : "worker-b";
        List<Object> reply = modules.list("XAUTOCLAIM", STREAM, GROUP, name, 0, "0-0", "COUNT", 50);
        List<String> claimed = new ArrayList<>();
        if (reply.size() > 1 && reply.get(1) instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof List<?> pair && !pair.isEmpty()) {
                    claimed.add(String.valueOf(pair.get(0)));
                }
            }
        }
        demo.cmd("XAUTOCLAIM " + STREAM + " " + GROUP + " " + name + " 0 0-0 COUNT 50  → " + claimed.size() + " claimed");
        if (!claimed.isEmpty()) {
            Long acked = redis.opsForStream().acknowledge(STREAM, GROUP, claimed.toArray(String[]::new));
            demo.cmd("XACK " + STREAM + " " + GROUP + " … " + claimed.size() + " ids  → " + acked);
        }
        Map<String, Object> data = state(demo);
        data.put("consumer", name);
        data.put("claimed", claimed);
        demo.check("No entries left pending after recovery", ((Number) data.get("pendingTotal")).longValue() == 0,
                "XPENDING total = " + data.get("pendingTotal"));
        return demo.finish(data);
    }

    public DemoResult reset() {
        Demo demo = Demo.start("streams-reset");
        redis.delete(List.of(STREAM, "lab:stream:seq"));
        demo.cmd("DEL " + STREAM);
        ensureGroup(demo);
        return demo.finish(state(demo));
    }

    private void ensureGroup(Demo demo) {
        try {
            modules.scalar("XGROUP", "CREATE", STREAM, GROUP, "0", "MKSTREAM");
            demo.cmd("XGROUP CREATE " + STREAM + " " + GROUP + " 0 MKSTREAM  → OK");
        } catch (RedisCommandExecutionException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private Map<String, Object> state(Demo demo) {
        Long length = redis.opsForStream().size(STREAM);
        List<MapRecord<String, Object, Object>> recent = redis.opsForStream().reverseRange(STREAM, Range.unbounded(),
                Limit.limit().count(12));
        PendingMessagesSummary pending = redis.opsForStream().pending(STREAM, GROUP);
        demo.cmd("XLEN " + STREAM + "  → " + length);
        demo.cmd("XREVRANGE " + STREAM + " + - COUNT 12");
        demo.cmd("XPENDING " + STREAM + " " + GROUP + "  → " + (pending == null ? 0 : pending.getTotalPendingMessages()) + " pending");
        Map<String, Long> perConsumer = new LinkedHashMap<>();
        if (pending != null) {
            perConsumer.putAll(pending.getPendingMessagesPerConsumer());
        }
        String lastDelivered = "0-0";
        for (Object group : modules.list("XINFO", "GROUPS", STREAM)) {
            if (group instanceof List<?> fields) {
                for (int i = 0; i + 1 < fields.size(); i += 2) {
                    if ("last-delivered-id".equals(fields.get(i))) {
                        lastDelivered = String.valueOf(fields.get(i + 1));
                    }
                }
            }
        }
        demo.cmd("XINFO GROUPS " + STREAM + "  → last-delivered-id " + lastDelivered);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("length", length);
        data.put("recent", toEntries(recent));
        data.put("pendingTotal", pending == null ? 0 : pending.getTotalPendingMessages());
        data.put("pendingPerConsumer", perConsumer);
        data.put("lastDeliveredId", lastDelivered);
        return data;
    }

    private static List<Map<String, Object>> toEntries(List<MapRecord<String, Object, Object>> records) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (records != null) {
            for (MapRecord<String, Object, Object> record : records) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", record.getId().getValue());
                entry.put("fields", record.getValue());
                entries.add(entry);
            }
        }
        return entries;
    }

    private static boolean isIncreasing(List<String> ids) {
        for (int i = 1; i < ids.size(); i++) {
            if (RecordId.of(ids.get(i)).getTimestamp() < RecordId.of(ids.get(i - 1)).getTimestamp()
                    || (RecordId.of(ids.get(i)).getTimestamp().equals(RecordId.of(ids.get(i - 1)).getTimestamp())
                    && RecordId.of(ids.get(i)).getSequence() <= RecordId.of(ids.get(i - 1)).getSequence())) {
                return false;
            }
        }
        return true;
    }
}
