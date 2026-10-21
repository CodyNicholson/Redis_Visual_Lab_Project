package com.example.redislab.support;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.metrics.CommandLatencyRecorder;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Sends raw commands (JSON.*, FT.*, BF.*, TS.*, V*, HEXPIRE, ...) that Spring Data Redis has no
 * typed API for. {@code RedisConnection.execute} decodes unknown commands as a single bulk string,
 * which loses array replies, so this uses its own Lettuce connection with a nested-array output.
 * RESP2 is forced so module replies keep their documented flat-array shape.
 */
@Component
public class RedisModules implements DisposableBean {

    /** One keyword object per command name: Lettuce keys per-command state (such as metrics) by it. */
    private static final Map<String, ProtocolKeyword> KEYWORDS = new ConcurrentHashMap<>();

    private final ClientResources resources;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    public RedisModules(RedisConnectionDetails details) {
        RedisConnectionDetails.Standalone standalone = details.getStandalone();
        RedisURI.Builder uri = RedisURI.builder().withHost(standalone.getHost()).withPort(standalone.getPort());
        if (details.getPassword() != null) {
            uri.withPassword(details.getPassword().toCharArray());
        }
        // Latency histograms are per command type; the lab does not need them on this connection
        resources = DefaultClientResources.builder().commandLatencyRecorder(CommandLatencyRecorder.disabled()).build();
        client = RedisClient.create(resources, uri.build());
        client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build());
        connection = client.connect();
    }

    /** Runs a command whose reply is an array; returns the elements (nested arrays stay nested). */
    public List<Object> list(String command, Object... args) {
        return connection.sync().dispatch(keyword(command), new NestedMultiOutput<>(StringCodec.UTF8), args(args));
    }

    /** Runs a command whose reply is a single value (string, integer or nil). */
    public Object scalar(String command, Object... args) {
        List<Object> reply = list(command, args);
        return reply.isEmpty() ? null : reply.get(0);
    }

    /** Sends many commands without waiting for each reply (pipelining), then waits for all of them. */
    public void pipeline(List<Object[]> commands) {
        List<RedisFuture<List<Object>>> futures = new ArrayList<>(commands.size());
        for (Object[] command : commands) {
            Object[] rest = new Object[command.length - 1];
            System.arraycopy(command, 1, rest, 0, rest.length);
            futures.add(connection.async().dispatch(keyword((String) command[0]),
                    new NestedMultiOutput<>(StringCodec.UTF8), args(rest)));
        }
        for (RedisFuture<List<Object>> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("Pipelined command failed: " + e.getMessage(), e);
            }
        }
    }

    private static CommandArgs<String, String> args(Object[] args) {
        CommandArgs<String, String> commandArgs = new CommandArgs<>(StringCodec.UTF8);
        for (Object arg : args) {
            commandArgs.add(String.valueOf(arg));
        }
        return commandArgs;
    }

    static ProtocolKeyword keyword(String command) {
        return KEYWORDS.computeIfAbsent(command.toUpperCase(Locale.ROOT), Keyword::new);
    }

    private record Keyword(String name) implements ProtocolKeyword {
        @Override
        public byte[] getBytes() {
            return name.getBytes(StandardCharsets.US_ASCII);
        }
    }

    @Override
    public void destroy() {
        connection.close();
        client.shutdown();
        resources.shutdown();
    }
}
