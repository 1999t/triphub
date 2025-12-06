package com.triphub.server.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于「时间戳 + Redis 自增序列」生成全局唯一 ID。
 * 高位为相对起始时间的秒数，低位为当日自增序号，整体有序且在分布式环境下不冲突。
 */
@Component
@RequiredArgsConstructor
public class RedisIdWorker {

    /**
     * 起始时间戳：2022-01-01 00:00:00（UTC），所有生成的 ID 时间部分都基于此偏移量。
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 序列号在 ID 中占用的 bit 位数（低 32 位为每日自增序列）。
     */
    private static final int COUNT_BITS = 32;

    private final StringRedisTemplate stringRedisTemplate;

    public Long nextId(String keyPrefix) {
        // 1. 计算当前时间相对起始时间的秒数，用作高位时间部分
        long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 使用 Redis 自增获得当日序列号，不同 keyPrefix 独立计数
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        String key = "icr:" + keyPrefix + ":" + date;
        Long count = stringRedisTemplate.opsForValue().increment(key);

        // 3. 将时间戳左移预留出序列位，再与序列号做或运算得到最终 ID
        return (timestamp << COUNT_BITS) | (count != null ? count : 0L);
    }
}


