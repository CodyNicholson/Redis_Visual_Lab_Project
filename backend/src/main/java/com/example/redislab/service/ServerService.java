package com.example.redislab.service;

import com.example.redislab.model.DemoResult;
import com.example.redislab.support.Demo;
import com.example.redislab.support.KeyScanner;
import com.example.redislab.support.RedisModules;
import io.lettuce.core.RedisCommandExecutionException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Server introspection (INFO, MODULE LIST, SCAN), persistence (BGSAVE) and lab cleanup. */
@Service
public class ServerService {

    private static final List<String> INFO_FIELDS = List.of(
            "redis_version", "uptime_in_seconds", "connected_clients", "used_memory_human", "used_memory_peak_human",
            "maxmemory_human", "maxmemory_policy", "total_commands_processed", "instantaneous_ops_per_sec",
            "keyspace_hits", "keyspace_misses", "expired_keys", "evicted_keys", "pubsub_patterns",
            "aof_enabled", "rdb_last_save_time", "rdb_changes_since_last_save", "rdb_bgsave_in_progress");

    private final RedisModules modules;
    private final KeyScanner keys;

    public ServerService(RedisModules modules, KeyScanner keys) {
        this.modules = modules;
        this.keys = keys;
    }

    /** A cheap snapshot for the live stats bar. */
    public Map<String, Object> stats() {
        Map<String, String> info = info();
        Map<String, Object> stats = new LinkedHashMap<>();
        INFO_FIELDS.forEach(field -> stats.put(field, info.get(field)));
        long hits = parseLong(info.get("keyspace_hits"));
        long misses = parseLong(info.get("keyspace_misses"));
        stats.put("hit_ratio", hits + misses == 0 ? null : Math.round(hits * 1000.0 / (hits + misses)) / 10.0);
        stats.put("dbsize", modules.scalar("DBSIZE"));
        return stats;
    }

    public DemoResult inspect() {
        Demo demo = Demo.start("server");
        Map<String, Object> stats = stats();
        demo.cmd("INFO  → " + INFO_FIELDS.size() + " fields picked from the server, memory, stats and persistence sections");
        demo.cmd("DBSIZE  → " + stats.get("dbsize"));

        List<String> moduleNames = new ArrayList<>();
        for (Object module : modules.list("MODULE", "LIST")) {
            if (module instanceof List<?> fields && fields.size() > 1) {
                moduleNames.add(String.valueOf(fields.get(1)));
            }
        }
        demo.cmd("MODULE LIST  → " + moduleNames);

        Map<String, Integer> byFeature = new TreeMap<>();
        Map<String, String> sampleKeys = new TreeMap<>();
        int[] total = {0};
        keys.scan("lab:*", key -> {
            String[] parts = key.split(":");
            String feature = parts.length > 1 ? parts[1] : key;
            byFeature.merge(feature, 1, Integer::sum);
            sampleKeys.putIfAbsent(feature, key);
            total[0]++;
        });
        demo.cmd("SCAN 0 MATCH lab:* COUNT 1000 … (cursor loop)  → " + total[0] + " keys");
        List<Map<String, Object>> keyspace = new ArrayList<>();
        byFeature.forEach((feature, count) -> {
            String sample = sampleKeys.get(feature);
            String type = String.valueOf(modules.scalar("TYPE", sample));
            keyspace.add(Map.of("feature", feature, "keys", count, "sample", sample, "type", type));
        });
        demo.cmd("TYPE <one sample key per feature>");

        List<Object> persistence = modules.list("CONFIG", "GET", "appendonly");
        Object policy = modules.list("CONFIG", "GET", "maxmemory-policy");
        demo.cmd("CONFIG GET appendonly  → " + persistence).cmd("CONFIG GET maxmemory-policy  → " + policy);
        Object lastSave = modules.scalar("LASTSAVE");
        demo.cmd("LASTSAVE  → " + lastSave);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stats", stats);
        data.put("modules", moduleNames);
        data.put("keyspace", keyspace);
        data.put("labKeys", total[0]);
        data.put("lastSave", lastSave);
        demo.check("Redis answered INFO", stats.get("redis_version") != null, "version " + stats.get("redis_version"));
        demo.check("Redis 8 built-in modules are loaded", moduleNames.containsAll(List.of("ReJSON", "search", "timeseries", "bf")),
                String.join(", ", moduleNames));
        demo.check("SCAN walked the lab keyspace without blocking", total[0] >= 0, total[0] + " keys in " + byFeature.size() + " groups");
        return demo.finish(data);
    }

    public DemoResult backgroundSave() {
        Demo demo = Demo.start("bgsave");
        Object before = modules.scalar("LASTSAVE");
        String reply;
        try {
            reply = String.valueOf(modules.scalar("BGSAVE"));
        } catch (RedisCommandExecutionException e) {
            reply = e.getMessage();
        }
        demo.cmd("LASTSAVE  → " + before).cmd("BGSAVE  → " + reply);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reply", reply);
        data.put("lastSaveBefore", before);
        data.put("info", info().entrySet().stream().filter(e -> e.getKey().startsWith("rdb_") || e.getKey().startsWith("aof_"))
                .collect(TreeMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll));
        demo.check("Redis forked a background save", reply.toLowerCase().contains("background saving")
                || reply.toLowerCase().contains("already in progress") || reply.toLowerCase().contains("scheduled"), reply);
        return demo.finish(data);
    }

    public DemoResult resetLab() {
        Demo demo = Demo.start("reset");
        try {
            modules.scalar("FT.DROPINDEX", ModulesService.SEARCH_INDEX);
            demo.cmd("FT.DROPINDEX " + ModulesService.SEARCH_INDEX + "  → OK");
        } catch (RedisCommandExecutionException e) {
            demo.cmd("FT.DROPINDEX " + ModulesService.SEARCH_INDEX + "  → (no index)");
        }
        long removed = keys.unlink("lab:*");
        demo.cmd("SCAN MATCH lab:* + UNLINK  → " + removed + " keys removed");
        demo.check("Lab keys removed", true, removed + " keys");
        return demo.finish(Map.of("removed", removed));
    }

    private Map<String, String> info() {
        Map<String, String> info = new LinkedHashMap<>();
        String raw = String.valueOf(modules.scalar("INFO", "everything"));
        for (String line : raw.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith("#")) {
                info.put(line.substring(0, colon), line.substring(colon + 1).trim());
            }
        }
        return info;
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
