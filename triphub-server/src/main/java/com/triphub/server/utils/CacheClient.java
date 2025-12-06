package com.triphub.server.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.redis.RedisData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

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
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        try {
            String json = objectMapper.writeValueAsString(redisData);
            stringRedisTemplate.opsForValue().set(key, json);
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
            return null;
        }
        // 查询数据库
        R r = dbFallback.apply(id);
        //数据库查不到，先在 Redis 写一个空字符串，并设置 TTL（nullTtlMinutes）
        //下次有人请求同样的 Key，就直接返回空，不打数据库
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", nullTtlMinutes, TimeUnit.MINUTES);
            return null;
        }
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
            // 1. 缓存不存在：兜底走一次 DB，再写入逻辑过期缓存（适合未预热场景）
            if (!StringUtils.hasText(json)) {
                R dbResult = dbFallback.apply(id);
                if (dbResult == null) {
                    return null;
                }
                setWithLogicalExpire(key, dbResult, time, unit);
                return dbResult;
            }

            // 2. 缓存存在：反序列化并判断逻辑过期时间
            RedisData redisData = objectMapper.readValue(json, RedisData.class);
            R data = objectMapper.convertValue(redisData.getData(), type);
            LocalDateTime expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {
                return data;
            }
            // 已过期，尝试获取互斥锁重建缓存
            String lockKey = lockKeyPrefix + id;
            boolean isLock = tryLock(lockKey);
            if (isLock) {
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        R fresh = dbFallback.apply(id);
                        setWithLogicalExpire(key, fresh, time, unit);
                    } catch (Exception e) {
                        log.error("重建缓存失败", e);
                    } finally {
                        unLock(lockKey);
                    }
                });
            }
            return data;
        } catch (Exception e) {
            log.error("反序列化逻辑过期缓存失败", e);
            return null;
        }
    }

    private boolean tryLock(String key) {
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}


