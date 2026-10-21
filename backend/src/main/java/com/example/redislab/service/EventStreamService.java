package com.example.redislab.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Receives Redis pub/sub messages and key-expiry notifications and fans them out to every
 * browser connected to {@code /api/events/stream} as Server-Sent Events.
 */
@Service
public class EventStreamService implements MessageListener {

    public static final String EXPIRED_CHANNEL = "__keyevent@0__:expired";

    private static final Logger log = LoggerFactory.getLogger(EventStreamService.class);

    private final StringRedisTemplate redis;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<String, CountDownLatch> awaiting = new ConcurrentHashMap<>();

    public EventStreamService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Keyspace notifications are off by default; "Ex" turns on expired-key events. */
    @EventListener(ApplicationReadyEvent.class)
    public void enableExpiryNotifications() {
        try {
            redis.execute(connection -> {
                connection.serverCommands().setConfig("notify-keyspace-events", "Ex");
                return null;
            }, true);
        } catch (Exception e) {
            log.warn("Could not enable keyspace notifications: {}", e.getMessage());
        }
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, Map.of("type", "hello", "channel", "", "payload", "connected", "ts", System.currentTimeMillis()));
        return emitter;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        boolean expired = EXPIRED_CHANNEL.equals(channel);
        if (expired && !payload.startsWith("lab:")) {
            return;
        }
        CountDownLatch latch = awaiting.remove(channel + "\n" + payload);
        if (latch != null) {
            latch.countDown();
        }
        broadcast(Map.of(
                "type", expired ? "expired" : "message",
                "channel", channel,
                "payload", payload,
                "ts", System.currentTimeMillis()));
    }

    /** Registers interest in one message before it is published, so the pub/sub demo can time the round trip. */
    public CountDownLatch expect(String channel, String payload) {
        CountDownLatch latch = new CountDownLatch(1);
        awaiting.put(channel + "\n" + payload, latch);
        return latch;
    }

    public void forget(String channel, String payload) {
        awaiting.remove(channel + "\n" + payload);
    }

    public int subscriberCount() {
        return emitters.size();
    }

    /** Keeps idle SSE connections alive through proxies and tunnels. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    private void broadcast(Map<String, Object> event) {
        for (SseEmitter emitter : emitters) {
            send(emitter, event);
        }
    }

    private void send(SseEmitter emitter, Map<String, Object> event) {
        try {
            emitter.send(SseEmitter.event().name("redis-event").data(event));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }
}
