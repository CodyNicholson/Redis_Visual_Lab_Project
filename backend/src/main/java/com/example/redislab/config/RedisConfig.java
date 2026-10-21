package com.example.redislab.config;

import com.example.redislab.model.Product;
import com.example.redislab.service.EventStreamService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

    /**
     * By default Lettuce flushes every pipelined command to the socket as it is queued, which is
     * barely faster than not pipelining. Flushing once at the end sends the whole batch together.
     */
    @Bean
    public static BeanPostProcessor pipelineFlushOnClose() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof LettuceConnectionFactory factory) {
                    factory.setPipeliningFlushPolicy(LettuceConnection.PipeliningFlushPolicy.flushOnClose());
                }
                return bean;
            }
        };
    }

    /** Raw byte values: the catalog cache stores the exact JSON bytes Postgres produced. */
    @Bean
    public RedisTemplate<String, byte[]> bytesRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(RedisSerializer.byteArray());
        return template;
    }

    /** Spring Cache (@Cacheable) entries live under lab:spring:<cache>::<key> as readable JSON. */
    @Bean
    public RedisCacheManagerBuilderCustomizer labCacheCustomizer(@Value("${lab.spring-cache.ttl}") Duration ttl) {
        RedisCacheConfiguration productCache = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("lab:spring:")
                .entryTtl(ttl)
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(Product.class)));
        return builder -> builder.withCacheConfiguration("product", productCache);
    }

    /** Subscribes to the lab's pub/sub channels and to key-expiry events, relaying both to the browser. */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory,
                                                                       EventStreamService events) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(events, new PatternTopic("lab:*"));
        container.addMessageListener(events, new ChannelTopic(EventStreamService.EXPIRED_CHANNEL));
        return container;
    }
}
