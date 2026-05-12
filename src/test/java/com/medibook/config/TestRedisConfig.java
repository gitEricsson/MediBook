package com.medibook.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@Configuration
@Profile("test")
public class TestRedisConfig {

    @Bean
    @ConditionalOnProperty(name = "medibook.test.redis.mode", havingValue = "container")
    public LettuceConnectionFactory testRedisConnectionFactory(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {
        return new LettuceConnectionFactory(host, port);
    }

    @Bean
    @ConditionalOnProperty(name = "medibook.test.redis.mode", havingValue = "container")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        GenericJackson2JsonRedisSerializer jsonSerializer = jsonSerializer();

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnProperty(name = "medibook.test.redis.mode", havingValue = "container")
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @ConditionalOnProperty(name = "medibook.test.redis.mode", havingValue = "container")
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    @Bean
    @ConditionalOnProperty(name = "medibook.test.redis.mode", havingValue = "container")
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        GenericJackson2JsonRedisSerializer jsonSerializer = jsonSerializer();
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .withCacheConfiguration("users", config.entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration("doctors", config.entryTtl(Duration.ofMinutes(30)))
                .withCacheConfiguration("departments", config.entryTtl(Duration.ofHours(1)))
                .withCacheConfiguration("appointments", config.entryTtl(Duration.ofMinutes(5)))
                .withCacheConfiguration("notificationUnreadCounts", config.entryTtl(Duration.ofSeconds(60)))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "medibook.test.redis.mode", havingValue = "memory", matchIfMissing = true)
    public RedisTemplate<String, Object> inMemoryRedisTemplate() {
        return redisTemplateMock(new Store());
    }

    @Bean
    @ConditionalOnProperty(name = "medibook.test.redis.mode", havingValue = "memory", matchIfMissing = true)
    public StringRedisTemplate inMemoryStringRedisTemplate() {
        return stringRedisTemplateMock(new Store());
    }

    @Bean
    @ConditionalOnProperty(name = "medibook.test.redis.mode", havingValue = "memory", matchIfMissing = true)
    public RedisMessageListenerContainer inMemoryRedisMessageListenerContainer() {
        return mock(RedisMessageListenerContainer.class);
    }

    @Bean
    @ConditionalOnProperty(name = "medibook.test.redis.mode", havingValue = "memory", matchIfMissing = true)
    public CacheManager inMemoryCacheManager() {
        return new ConcurrentMapCacheManager("users", "doctors", "departments", "appointments", "notificationUnreadCounts");
    }

    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RedisTemplate<String, Object> redisTemplateMock(Store store) {
        RedisTemplate template = mock(RedisTemplate.class);
        ValueOperations valueOps = mock(ValueOperations.class);
        SetOperations setOps = mock(SetOperations.class);
        wireRedisOperations(template, valueOps, setOps, store);
        return template;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private StringRedisTemplate stringRedisTemplateMock(Store store) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations valueOps = mock(ValueOperations.class);
        SetOperations setOps = mock(SetOperations.class);
        wireRedisOperations(template, valueOps, setOps, store);
        doAnswer(invocation -> 0L).when(template).convertAndSend(anyString(), anyString());
        return template;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void wireRedisOperations(
            RedisTemplate template,
            ValueOperations valueOps,
            SetOperations setOps,
            Store store) {
        doReturn(valueOps).when(template).opsForValue();
        doReturn(setOps).when(template).opsForSet();

        doAnswer(invocation -> store.get(invocation.getArgument(0))).when(valueOps).get(anyString());
        doAnswer(invocation -> store.getAndDelete(invocation.getArgument(0))).when(valueOps).getAndDelete(anyString());
        doAnswer(invocation -> store.increment(invocation.getArgument(0))).when(valueOps).increment(anyString());
        doAnswer(invocation -> {
            store.set(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(valueOps).set(anyString(), any(), any(Duration.class));
        doAnswer(invocation -> store.setIfAbsent(
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2)))
                .when(valueOps).setIfAbsent(anyString(), any(), any(Duration.class));

        doAnswer(invocation -> store.setAdd(invocation.getArgument(0), varargs(invocation.getArgument(1))))
                .when(setOps).add(anyString(), any());
        doAnswer(invocation -> store.setRemove(invocation.getArgument(0), varargs(invocation.getArgument(1))))
                .when(setOps).remove(anyString(), any());
        doAnswer(invocation -> store.setMembers(invocation.getArgument(0)))
                .when(setOps).members(anyString());

        doAnswer(invocation -> store.delete(invocation.getArgument(0))).when(template).delete(anyString());
        doAnswer(invocation -> store.hasKey(invocation.getArgument(0))).when(template).hasKey(anyString());
        doAnswer(invocation -> store.expire(invocation.getArgument(0), invocation.getArgument(1)))
                .when(template).expire(anyString(), any(Duration.class));
        doAnswer(invocation -> executePipelined(invocation.getArgument(0), valueOps, setOps, store))
                .when(template).executePipelined(Mockito.<SessionCallback<?>>any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private java.util.List<Object> executePipelined(
            SessionCallback callback,
            ValueOperations valueOps,
            SetOperations setOps,
            Store store) {
        java.util.List<Object> results = new ArrayList<>();
        RedisOperations operations = mock(RedisOperations.class);
        doReturn(valueOps).when(operations).opsForValue();
        doReturn(setOps).when(operations).opsForSet();
        doAnswer(invocation -> {
            Boolean result = store.hasKey(invocation.getArgument(0));
            results.add(result);
            return result;
        }).when(operations).hasKey(anyString());
        doAnswer(invocation -> store.delete(invocation.getArgument(0))).when(operations).delete(anyString());
        doAnswer(invocation -> store.expire(invocation.getArgument(0), invocation.getArgument(1)))
                .when(operations).expire(anyString(), any(Duration.class));
        callback.execute(operations);
        return results;
    }

    private Object[] varargs(Object argument) {
        if (argument instanceof Object[] values) {
            return values;
        }
        return new Object[] { argument };
    }

    private static final class Store {
        private final ConcurrentMap<String, Entry> values = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Set<Object>> sets = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Instant> expiries = new ConcurrentHashMap<>();

        Object get(String key) {
            purgeExpired(key);
            Entry entry = values.get(key);
            return entry == null ? null : entry.value();
        }

        void set(String key, Object value, Duration ttl) {
            Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
            values.put(key, new Entry(value, expiresAt));
            if (expiresAt == null) {
                expiries.remove(key);
            } else {
                expiries.put(key, expiresAt);
            }
        }

        Boolean setIfAbsent(String key, Object value, Duration ttl) {
            purgeExpired(key);
            if (hasKey(key)) {
                return false;
            }
            set(key, value, ttl);
            return true;
        }

        Object getAndDelete(String key) {
            purgeExpired(key);
            Entry entry = values.remove(key);
            expiries.remove(key);
            return entry == null ? null : entry.value();
        }

        Long increment(String key) {
            purgeExpired(key);
            Object current = get(key);
            long next = current == null ? 1L : Long.parseLong(current.toString()) + 1L;
            set(key, String.valueOf(next), null);
            return next;
        }

        Long setAdd(String key, Object[] valuesToAdd) {
            purgeExpired(key);
            Set<Object> set = sets.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
            long added = Arrays.stream(valuesToAdd).filter(set::add).count();
            return added;
        }

        Long setRemove(String key, Object[] valuesToRemove) {
            purgeExpired(key);
            Set<Object> set = sets.get(key);
            if (set == null) {
                return 0L;
            }
            long removed = Arrays.stream(valuesToRemove).filter(set::remove).count();
            if (set.isEmpty()) {
                sets.remove(key);
            }
            return removed;
        }

        Set<Object> setMembers(String key) {
            purgeExpired(key);
            Set<Object> set = sets.get(key);
            return set == null ? Collections.emptySet() : Set.copyOf(set);
        }

        Boolean delete(String key) {
            purgeExpired(key);
            boolean removed = values.remove(key) != null;
            removed = sets.remove(key) != null || removed;
            expiries.remove(key);
            return removed;
        }

        Boolean hasKey(String key) {
            purgeExpired(key);
            return values.containsKey(key) || sets.containsKey(key);
        }

        Boolean expire(String key, Duration ttl) {
            purgeExpired(key);
            if (!hasKey(key)) {
                return false;
            }
            Instant expiresAt = Instant.now().plus(ttl);
            Entry entry = values.get(key);
            if (entry != null) {
                values.put(key, new Entry(entry.value(), expiresAt));
            }
            expiries.put(key, expiresAt);
            return true;
        }

        private void purgeExpired(String key) {
            Instant valueExpiry = values.getOrDefault(key, new Entry(null, null)).expiresAt();
            Instant keyExpiry = expiries.get(key);
            Instant expiresAt = valueExpiry != null ? valueExpiry : keyExpiry;
            if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
                values.remove(key);
                sets.remove(key);
                expiries.remove(key);
            }
        }
    }

    private record Entry(Object value, Instant expiresAt) {
    }
}
