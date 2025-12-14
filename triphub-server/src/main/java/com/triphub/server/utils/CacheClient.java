package com.triphub.server.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.constant.RedisConstants;
import com.triphub.common.redis.RedisData;
import com.triphub.server.metrics.MetricsRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MetricsRecorder metricsRecorder;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 逻辑过期重建互斥锁 TTL（秒）。
     * 说明：锁 TTL 太短会导致 DB 慢时锁提前过期，引发并发重建（击穿回潮）。
     */
    private static final long LOCK_TTL_SECONDS = 30L;
    /**
     * 逻辑过期缓存的“物理 TTL”倍数（用于最终回收，避免 key 永久驻留）。
     * 逻辑过期 = 允许返回旧值 + 异步重建；物理 TTL = 最终清理冷数据。
     */
    private static final long LOGICAL_EXPIRE_PHYSICAL_TTL_MULTIPLIER = 5L;
    /**
     * 逻辑过期缓存物理 TTL 上限（秒），防止误配置导致 key 常驻。
     */
    private static final long LOGICAL_EXPIRE_PHYSICAL_TTL_MAX_SECONDS = TimeUnit.DAYS.toSeconds(7);

    /**
     * 安全解锁脚本：只有 value(token) 一致时才删除，避免误删别人的锁。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end",
            Long.class
    );

    public void set(String key, Object value, long time, TimeUnit unit) {
        try {
            String json = objectMapper.writeValueAsString(value);
            stringRedisTemplate.opsForValue().set(key, json, time, unit);
        } catch (JsonProcessingException e) {
            log.error("序列化缓存对象失败", e);
        }
    }

    public void setWithLogicalExpire(String key, Object value, long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        long logicalSeconds = unit.toSeconds(time);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(logicalSeconds));
        try {
            String json = objectMapper.writeValueAsString(redisData);
            // 逻辑过期缓存也应设置一个更长的物理 TTL，避免冷 key 永久驻留
            long physicalSeconds = calcPhysicalTtlSeconds(logicalSeconds);
            stringRedisTemplate.opsForValue().set(key, json, physicalSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.error("序列化逻辑过期缓存失败", e);
        }
    }

    /**
     * 缓存穿透：空值缓存防护
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, long time, TimeUnit unit, long nullTtlMinutes) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.hasText(json)) {
            // 命中缓存
            metricsRecorder.recordTripCacheHit(true);
            try {
                return objectMapper.readValue(json, type);
            } catch (Exception e) {
                log.error("反序列化缓存失败", e);
                return null;
            }
        }
        // 命中空值
       // 这里判断 json != null，但上面 hasText(json) 已经是 false，说明 json 是空字符串 ""
       // 这是空值缓存的命中
        if (json != null) {
            metricsRecorder.recordTripCacheHit(true);
            return null;
        }
        // 查询数据库
        R r = dbFallback.apply(id);
        //数据库查不到，先在 Redis 写一个空字符串，并设置 TTL（nullTtlMinutes）
        //下次有人请求同样的 Key，就直接返回空，不打数据库
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", nullTtlMinutes, TimeUnit.MINUTES);
            metricsRecorder.recordTripCacheHit(false);
            return null;
        }
        metricsRecorder.recordTripCacheHit(false);
        set(key, r, time, unit);
        return r;
    }

    /**
     * 逻辑过期 + 互斥锁重建缓存，防止缓存击穿
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, long time, TimeUnit unit, String lockKeyPrefix) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        try {
            // 0. 命中空值缓存：直接返回空，避免缓存穿透
            if (json != null && !StringUtils.hasText(json)) {
                metricsRecorder.recordTripCacheHit(true);
                return null;
            }

            // 1. 缓存不存在：兜底走一次 DB，再写入逻辑过期缓存（适合未预热场景）
            if (json == null) {
                metricsRecorder.recordTripCacheHit(false);
                R dbResult = dbFallback.apply(id);
                if (dbResult == null) {
                    // DB 不存在：写入短 TTL 空值，防穿透
                    stringRedisTemplate.opsForValue()
                            .set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                setWithLogicalExpire(key, dbResult, time, unit);
                return dbResult;
            }

            // 2. 缓存存在：反序列化并判断逻辑过期时间
            metricsRecorder.recordTripCacheHit(true);
            RedisData redisData = objectMapper.readValue(json, RedisData.class);
            R data = objectMapper.convertValue(redisData.getData(), type);
            LocalDateTime expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {
                return data;
            }
            // 已过期，尝试获取互斥锁重建缓存
            String lockKey = lockKeyPrefix + id;
            String lockToken = tryLock(lockKey);
            if (lockToken != null) {
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        // 双重检查：避免锁获取成功但缓存已被其他线程刷新时仍回源 DB
                        String latestJson = stringRedisTemplate.opsForValue().get(key);
                        if (latestJson != null && !StringUtils.hasText(latestJson)) {
                            // 仍是空值缓存：无需重建
                            return;
                        }
                        if (StringUtils.hasText(latestJson)) {
                            RedisData latestRedisData = objectMapper.readValue(latestJson, RedisData.class);
                            LocalDateTime latestExpireTime = latestRedisData.getExpireTime();
                            if (latestExpireTime != null && latestExpireTime.isAfter(LocalDateTime.now())) {
                                return;
                            }
                        }

                        R fresh = dbFallback.apply(id);
                        // 边界点修复：DB 返回 null 时，不允许写回“空对象 + 未来逻辑过期时间”
                        // 否则可能导致真实存在的数据被长期遮蔽（取决于上层对 null 的处理）。
                        if (fresh == null) {
                            stringRedisTemplate.opsForValue()
                                    .set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                            return;
                        }
                        setWithLogicalExpire(key, fresh, time, unit);
                    } catch (Exception e) {
                        log.error("重建缓存失败", e);
                    } finally {
                        unLock(lockKey, lockToken);
                    }
                });
            }
            return data;
        } catch (Exception e) {
            log.error("反序列化逻辑过期缓存失败", e);
            return null;
        }
    }

    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success) ? token : null;
    }

    private void unLock(String key, String token) {
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        } catch (Exception e) {
            // 解锁失败不应影响主流程，避免 finally 抛出异常吞掉上层异常
            log.warn("释放锁失败: key={}", key, e);
        }
    }

    private long calcPhysicalTtlSeconds(long logicalSeconds) {
        long physical;
        try {
            physical = Math.multiplyExact(logicalSeconds, LOGICAL_EXPIRE_PHYSICAL_TTL_MULTIPLIER);
        } catch (ArithmeticException e) {
            physical = LOGICAL_EXPIRE_PHYSICAL_TTL_MAX_SECONDS;
        }
        // 至少比逻辑过期长一点点
        physical = Math.max(physical, logicalSeconds + 60);
        // 上限保护
        return Math.min(physical, LOGICAL_EXPIRE_PHYSICAL_TTL_MAX_SECONDS);
    }
}


