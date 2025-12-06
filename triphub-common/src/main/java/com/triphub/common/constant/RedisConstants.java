package com.triphub.common.constant;

public class RedisConstants {

    private RedisConstants() {
    }

    /** 登录用户信息前缀 login:user:token */
    public static final String LOGIN_USER_KEY = "login:user:";

    /** 行程缓存前缀 cache:trip:tripId */
    public static final String CACHE_TRIP_KEY = "cache:trip:";

    /** 行程逻辑过期重建锁前缀 lock:trip:id */
    public static final String LOCK_TRIP_KEY = "lock:trip:";

    /** 热门行程 ZSet key */
    public static final String HOT_TRIP_ZSET = "hot:trip";

    /** 热门目的地 ZSet key */
    public static final String HOT_DEST_ZSET = "hot:dest";

    /** 秒杀库存 key 前缀 seckill:stock:activityId */
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";

    /** 秒杀下单用户集合 key 前缀 seckill:order:activityId */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";

    /** 秒杀下单分布式锁前缀 lock:seckill:order:userId */
    public static final String LOCK_SECKILL_ORDER = "lock:seckill:order:";

    /** 缓存空值 TTL（分钟） */
    public static final long CACHE_NULL_TTL = 2L;

    /** 行程缓存 TTL（分钟） */
    public static final long CACHE_TRIP_TTL = 30L;
}


