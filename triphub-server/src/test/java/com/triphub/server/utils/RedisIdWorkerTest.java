package com.triphub.server.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * RedisIdWorker 的基础单元测试：
 * - 验证生成的 ID 非空；
 * - 验证在同一时间窗口内多次调用时，ID 单调递增（依赖 Redis 自增序列）。
 */
@ExtendWith(MockitoExtension.class)
class RedisIdWorkerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisIdWorker redisIdWorker;

    @BeforeEach
    void setUp() {
        // 对 opsForValue() 的调用进行 lenient stub，避免对其它测试造成影响
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 每次调用 increment 时返回递增的序列号
        when(valueOperations.increment(anyString())).thenReturn(1L, 2L, 3L);

        redisIdWorker = new RedisIdWorker(stringRedisTemplate);
    }

    @Test
    void nextIdShouldGenerateMonotonicallyIncreasingIds() {
        Long id1 = redisIdWorker.nextId("order");
        Long id2 = redisIdWorker.nextId("order");

        assertNotNull(id1);
        assertNotNull(id2);
        // 由于低位是 Redis 自增序列，相同时间窗口下第二次调用的 ID 应当大于第一次
        assertTrue(id2 > id1, "second id should be greater than first id");
    }
}


