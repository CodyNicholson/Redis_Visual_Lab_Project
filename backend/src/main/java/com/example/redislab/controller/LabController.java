package com.example.redislab.controller;

import com.example.redislab.model.DemoResult;
import com.example.redislab.service.DataStructuresService;
import com.example.redislab.service.EventStreamService;
import com.example.redislab.service.GeoService;
import com.example.redislab.service.ModulesService;
import com.example.redislab.service.PatternsService;
import com.example.redislab.service.ProbabilisticService;
import com.example.redislab.service.ServerService;
import com.example.redislab.service.StreamsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * One endpoint per lab button. Inputs are clamped because the lab can be public: no request
 * may start more threads, keys or commands than the demo needs.
 */
@RestController
@RequestMapping("/api")
public class LabController {

    private final DataStructuresService structures;
    private final PatternsService patterns;
    private final StreamsService streams;
    private final ProbabilisticService probabilistic;
    private final GeoService geo;
    private final ModulesService modules;
    private final ServerService server;
    private final EventStreamService events;

    public LabController(DataStructuresService structures, PatternsService patterns, StreamsService streams,
                         ProbabilisticService probabilistic, GeoService geo, ModulesService modules,
                         ServerService server, EventStreamService events) {
        this.structures = structures;
        this.patterns = patterns;
        this.streams = streams;
        this.probabilistic = probabilistic;
        this.geo = geo;
        this.modules = modules;
        this.server = server;
        this.events = events;
    }

    // ---- Core data types ---------------------------------------------------------------

    @PostMapping("/lab/strings")
    public DemoResult strings() {
        return structures.strings();
    }

    @PostMapping("/lab/strings/counter")
    public DemoResult counter(@RequestParam(defaultValue = "8") int threads,
                              @RequestParam(defaultValue = "250") int increments) throws InterruptedException {
        return structures.counter(clamp(threads, 2, 16), clamp(increments, 10, 1000));
    }

    @PostMapping("/lab/ttl")
    public DemoResult ttl(@RequestParam(defaultValue = "10") int seconds) {
        return structures.ttl(clamp(seconds, 2, 120));
    }

    @GetMapping("/lab/ttl")
    public DemoResult ttlStatus() {
        return structures.ttlStatus();
    }

    @PostMapping("/lab/hashes")
    public DemoResult hashes() {
        return structures.hashes();
    }

    @GetMapping("/lab/hashes")
    public DemoResult hashStatus() {
        return structures.hashStatus();
    }

    @PostMapping("/lab/lists/enqueue")
    public DemoResult enqueue(@RequestParam(defaultValue = "5") int count) {
        return structures.enqueue(clamp(count, 1, 20));
    }

    @PostMapping("/lab/lists/work")
    public DemoResult work(@RequestParam(defaultValue = "3") int count) {
        return structures.work(clamp(count, 1, 20));
    }

    @PostMapping("/lab/sets")
    public DemoResult sets() {
        return structures.sets();
    }

    @PostMapping("/lab/leaderboard")
    public DemoResult leaderboard(@RequestParam(defaultValue = "15") int rounds) {
        return structures.leaderboard(clamp(rounds, 1, 50));
    }

    @PostMapping("/lab/leaderboard/reset")
    public DemoResult resetLeaderboard() {
        return structures.resetLeaderboard();
    }

    // ---- Patterns ----------------------------------------------------------------------

    @PostMapping("/lab/ratelimit/hit")
    public DemoResult rateLimitHit() {
        return patterns.rateLimitHit("browser");
    }

    @PostMapping("/lab/ratelimit/burst")
    public DemoResult rateLimitBurst(@RequestParam(defaultValue = "12") int requests) {
        return patterns.rateLimitBurst("burst", clamp(requests, 1, 30));
    }

    @PostMapping("/lab/lock")
    public DemoResult lock(@RequestParam(defaultValue = "5") int workers) throws InterruptedException {
        return patterns.lock(clamp(workers, 2, 8));
    }

    @PostMapping("/lab/transactions/transfer")
    public DemoResult transfer(@RequestParam(defaultValue = "25") int amount) {
        return patterns.transfer(clamp(amount, 1, 100));
    }

    @PostMapping("/lab/transactions/watch")
    public DemoResult watch() {
        return patterns.watchConflict();
    }

    @PostMapping("/lab/lua")
    public DemoResult lua(@RequestParam(defaultValue = "20") int stock,
                          @RequestParam(defaultValue = "100") int buyers) throws InterruptedException {
        return patterns.flashSale(clamp(stock, 1, 100), clamp(buyers, 1, 200));
    }

    @PostMapping("/lab/pipeline")
    public DemoResult pipeline(@RequestParam(defaultValue = "5000") int commands) {
        return patterns.pipeline(clamp(commands, 100, 20_000));
    }

    @PostMapping("/lab/pubsub")
    public DemoResult publish(@RequestParam(defaultValue = "lab:chat") String channel,
                              @RequestParam(defaultValue = "hello from the lab") String message) throws InterruptedException {
        return patterns.publish(channel, message);
    }

    // ---- Streams -----------------------------------------------------------------------

    @PostMapping("/lab/streams/produce")
    public DemoResult produce(@RequestParam(defaultValue = "5") int count) {
        return streams.produce(clamp(count, 1, 20));
    }

    @PostMapping("/lab/streams/consume")
    public DemoResult consume(@RequestParam(defaultValue = "worker-a") String consumer,
                              @RequestParam(defaultValue = "3") int count,
                              @RequestParam(defaultValue = "true") boolean ack) {
        return streams.consume(consumer, clamp(count, 1, 20), ack);
    }

    @PostMapping("/lab/streams/recover")
    public DemoResult recover(@RequestParam(defaultValue = "worker-b") String consumer) {
        return streams.recover(consumer);
    }

    @PostMapping("/lab/streams/reset")
    public DemoResult resetStream() {
        return streams.reset();
    }

    // ---- Probabilistic & geo -----------------------------------------------------------

    @PostMapping("/lab/hyperloglog")
    public DemoResult hyperLogLog(@RequestParam(defaultValue = "100000") int visits) {
        return probabilistic.hyperLogLog(clamp(visits, 1000, 300_000));
    }

    @PostMapping("/lab/bitmaps")
    public DemoResult bitmaps(@RequestParam(defaultValue = "1000") int users) {
        return probabilistic.bitmaps(clamp(users, 64, 10_000));
    }

    @PostMapping("/lab/bloom")
    public DemoResult bloom(@RequestParam(defaultValue = "5000") int names) {
        return probabilistic.bloom(clamp(names, 1000, 50_000));
    }

    @GetMapping("/lab/bloom/check")
    public DemoResult bloomCheck(@RequestParam(defaultValue = "user_42") String name) {
        return probabilistic.bloomCheck(name.length() > 64 ? name.substring(0, 64) : name);
    }

    @PostMapping("/lab/geo")
    public DemoResult geo(@RequestParam(defaultValue = "-87.6298") double lon,
                          @RequestParam(defaultValue = "41.8781") double lat,
                          @RequestParam(defaultValue = "800") double radiusKm) {
        return geo.search(Math.max(-180, Math.min(lon, 180)), Math.max(-85, Math.min(lat, 85)),
                Math.max(10, Math.min(radiusKm, 5000)));
    }

    // ---- Redis 8 data types ------------------------------------------------------------

    @PostMapping("/lab/json")
    public DemoResult json() {
        return modules.json();
    }

    @PostMapping("/lab/search")
    public DemoResult search(@RequestParam(defaultValue = "leather") String q,
                             @RequestParam(required = false) String category,
                             @RequestParam(required = false) Double maxPrice) {
        return modules.search(q.length() > 60 ? q.substring(0, 60) : q, category,
                maxPrice == null ? null : Math.max(0, Math.min(maxPrice, 10_000)));
    }

    @PostMapping("/lab/timeseries")
    public DemoResult timeSeries(@RequestParam(defaultValue = "6") int hours) {
        return modules.timeSeries(clamp(hours, 1, 24));
    }

    @PostMapping("/lab/vectors")
    public DemoResult vectors(@RequestParam(defaultValue = "#e4572e") String color,
                              @RequestParam(defaultValue = "false") boolean onlyLight) {
        return modules.vectors(color, onlyLight);
    }

    // ---- Server ------------------------------------------------------------------------

    @GetMapping("/lab/server")
    public DemoResult inspect() {
        return server.inspect();
    }

    @GetMapping("/lab/server/stats")
    public Map<String, Object> stats() {
        return server.stats();
    }

    @PostMapping("/lab/server/bgsave")
    public DemoResult backgroundSave() {
        return server.backgroundSave();
    }

    @PostMapping("/lab/reset")
    public DemoResult reset() {
        return server.resetLab();
    }

    @GetMapping("/events/stream")
    public SseEmitter events() {
        return events.subscribe();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
