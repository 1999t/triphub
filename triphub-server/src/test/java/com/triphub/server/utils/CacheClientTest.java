package com.triphub.server.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.server.metrics.MetricsRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CacheClient 的基础单元测试：
 * - 覆盖缓存命中场景；
 * - 覆盖缓存穿透（空值缓存）场景。
 *
 * 说明：这里使用 Mockito 模拟 Redis 行为，不依赖真实 Redis 实例，保持测试简单可控。
 */
@ExtendWith(MockitoExtension.class)
class CacheClientTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private MetricsRecorder metricsRecorder;

    private ObjectMapper objectMapper;
    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        // 与 Spring Boot 默认行为对齐：支持 Java Time（LocalDateTime）序列化/反序列化
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheClient = new CacheClient(stringRedisTemplate, objectMapper, metricsRecorder);
    }

    @Test
    void queryWithPassThrough_shouldReturnCachedValue_whenCacheHit() throws Exception {
        String keyPrefix = "cache:test:";
        Long id = 1L;
        TestDto dto = new TestDto();
        dto.setId(id);
        dto.setName("cached");

        String json = objectMapper.writeValueAsString(dto);
        when(valueOperations.get(keyPrefix + id)).thenReturn(json);

        Function<Long, TestDto> dbFallback = unused -> {
            throw new IllegalStateException("dbFallback should not be called when cache hit");
        };

        TestDto result = cacheClient.queryWithPassThrough(
                keyPrefix, id, TestDto.class, dbFallback,
                10, TimeUnit.MINUTES, 5
        );

        assertEquals(id, result.getId());
        assertEquals("cached", result.getName());
        // 命中缓存时不会触发写 Redis
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void queryWithPassThrough_shouldCacheEmptyAndReturnNull_whenDbReturnsNull() {
        String keyPrefix = "cache:test:";
        Long id = 2L;

        // 第一次读取缓存为空（未命中且不是空值缓存）
        when(valueOperations.get(keyPrefix + id)).thenReturn(null);

        Function<Long, TestDto> dbFallback = unused -> null;

        TestDto result = cacheClient.queryWithPassThrough(
                keyPrefix, id, TestDto.class, dbFallback,
                10, TimeUnit.MINUTES, 3
        );

        assertNull(result);
        // 数据库返回 null 时，会写入空字符串并设置短 TTL，防止缓存穿透
        verify(valueOperations).set(eq(keyPrefix + id), eq(""), eq(3L), eq(TimeUnit.MINUTES));
    }

    @Test
    void queryWithLogicalExpire_shouldReturnNull_whenHitNullCache() {
        String keyPrefix = "cache:test:";
        Long id = 3L;
        when(valueOperations.get(keyPrefix + id)).thenReturn("");

        Function<Long, TestDto> dbFallback = unused -> {
            throw new IllegalStateException("dbFallback should not be called when hit null-cache");
        };

        TestDto result = cacheClient.queryWithLogicalExpire(
                keyPrefix, id, TestDto.class, dbFallback,
                10, TimeUnit.MINUTES, "lock:test:"
        );
        assertNull(result);
    }

    @Test
    void queryWithLogicalExpire_shouldCacheNullWithShortTtl_whenCacheMissAndDbReturnsNull() {
        String keyPrefix = "cache:test:";
        Long id = 4L;
        when(valueOperations.get(keyPrefix + id)).thenReturn(null);

        Function<Long, TestDto> dbFallback = unused -> null;

        TestDto result = cacheClient.queryWithLogicalExpire(
                keyPrefix, id, TestDto.class, dbFallback,
                10, TimeUnit.MINUTES, "lock:test:"
        );

        assertNull(result);
        verify(valueOperations).set(eq(keyPrefix + id), eq(""), eq(2L), eq(TimeUnit.MINUTES));
    }

    @Test
    void queryWithLogicalExpire_shouldReturnCachedValue_whenNotExpired() throws Exception {
        String keyPrefix = "cache:test:";
        Long id = 5L;
        TestDto dto = new TestDto();
        dto.setId(id);
        dto.setName("cached");

        // 构造逻辑过期缓存：expireTime 在未来
        com.triphub.common.redis.RedisData redisData = new com.triphub.common.redis.RedisData();
        redisData.setData(dto);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(10));
        String json = objectMapper.writeValueAsString(redisData);
        when(valueOperations.get(keyPrefix + id)).thenReturn(json);

        Function<Long, TestDto> dbFallback = unused -> {
            throw new IllegalStateException("dbFallback should not be called when cache not expired");
        };

        TestDto result = cacheClient.queryWithLogicalExpire(
                keyPrefix, id, TestDto.class, dbFallback,
                10, TimeUnit.MINUTES, "lock:test:"
        );
        assertEquals(id, result.getId());
        assertEquals("cached", result.getName());
    }

    /**
     * 测试用简单 DTO。
     */
    private static class TestDto {
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}


