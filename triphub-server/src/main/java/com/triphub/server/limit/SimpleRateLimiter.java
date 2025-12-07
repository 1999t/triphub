package com.triphub.server.limit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的简单限流器：
 * - 使用固定时间窗口 + 计数的方式控制接口访问频率；
 * - 不追求算法上的完美精确，重点是实现「简单可靠、易于理解和说明」的防刷能力。
 *
 * 说明：
 * - 调用方需要自己决定限流维度（例如 IP、手机号、userId），并传入唯一 key；
 * - 达到上限后返回 false，由上层接口统一返回「请求过于频繁，请稍后再试」类错误文案。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SimpleRateLimiter {

    private static final String PREFIX = "rl:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 在给定时间窗口内做简单的计数限流。
     *
     * @param bizKey       业务前缀，例如 sendCode:ip、sendCode:phone、seckill:user
     * @param identify     限流维度标识，如 IP 地址、手机号、userId
     * @param windowSecond 时间窗口（秒）
     * @param maxCount     窗口内允许的最大次数
     * @return true 表示允许本次请求，false 表示超过阈值
     */
    public boolean tryAcquire(String bizKey, String identify, long windowSecond, long maxCount) {
        if (identify == null) {
            identify = "unknown";
        }
        String key = PREFIX + bizKey + ":" + identify;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count == null) {
            return true;
        }
        if (count == 1L) {
            stringRedisTemplate.expire(key, windowSecond, TimeUnit.SECONDS);
        }
        boolean allowed = count <= maxCount;
        if (!allowed) {
            log.warn("限流触发: bizKey={}, identify={}, windowSecond={}, maxCount={}, current={}",
                    bizKey, identify, windowSecond, maxCount, count);
        }
        return allowed;
    }
}


