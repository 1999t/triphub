package com.triphub.common.constant;

public class RedisConstants {

    private RedisConstants() {
    }

    /** 登录验证码前缀 login:code:phone */
    public static final String LOGIN_CODE_KEY = "login:code:";

    /** 登录验证码 TTL（分钟） */
    public static final long LOGIN_CODE_TTL = 5L;

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

    /** 行程浏览量增量 Hash key（field=tripId, value=delta） */
    public static final String TRIP_VIEW_COUNT_DELTA_HASH = "trip:view:delta";

    /** 行程浏览量增量 Hash key TTL（小时） */
    public static final long TRIP_VIEW_COUNT_DELTA_TTL_HOURS = 48L;

    /** 缓存空值 TTL（分钟） */
    public static final long CACHE_NULL_TTL = 2L;

    /** 行程缓存 TTL（分钟） */
    public static final long CACHE_TRIP_TTL = 30L;
}


