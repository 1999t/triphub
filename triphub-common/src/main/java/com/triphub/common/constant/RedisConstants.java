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

    /** 热门行程日榜 ZSet key 前缀：hot:trip:day:yyyyMMdd */
    public static final String HOT_TRIP_DAY_ZSET_PREFIX = "hot:trip:day:";

    /** 热门行程周榜 ZSet key 前缀：hot:trip:week:YYYYww */
    public static final String HOT_TRIP_WEEK_ZSET_PREFIX = "hot:trip:week:";

    /** 热门目的地日榜 ZSet key 前缀：hot:dest:day:yyyyMMdd */
    public static final String HOT_DEST_DAY_ZSET_PREFIX = "hot:dest:day:";

    /** 热门目的地周榜 ZSet key 前缀：hot:dest:week:YYYYww */
    public static final String HOT_DEST_WEEK_ZSET_PREFIX = "hot:dest:week:";

    /** 日榜 TTL（天） */
    public static final long HOT_DAY_TTL_DAYS = 3L;

    /** 周榜 TTL（天） */
    public static final long HOT_WEEK_TTL_DAYS = 15L;

    /** 行程浏览量增量 Hash key（field=tripId, value=delta） */
    public static final String TRIP_VIEW_COUNT_DELTA_HASH = "trip:view:delta";

    /** 行程浏览量增量 Hash key TTL（小时） */
    public static final long TRIP_VIEW_COUNT_DELTA_TTL_HOURS = 48L;

    /** 浏览量抗刷去重 key 前缀：trip:view:dedup:{userId}:{tripId} */
    public static final String TRIP_VIEW_DEDUP_KEY_PREFIX = "trip:view:dedup:";

    /** 浏览量去重窗口（秒）：同一用户短时间重复刷新不计数 */
    public static final long TRIP_VIEW_DEDUP_TTL_SECONDS = 10L;

    /** 行程摘要缓存前缀 cache:trip:summary:{id} */
    public static final String CACHE_TRIP_SUMMARY_KEY = "cache:trip:summary:";

    /** 行程摘要缓存 TTL（分钟） */
    public static final long CACHE_TRIP_SUMMARY_TTL_MINUTES = 30L;

    /** 缓存空值 TTL（分钟） */
    public static final long CACHE_NULL_TTL = 2L;

    /** 行程缓存 TTL（分钟） */
    public static final long CACHE_TRIP_TTL = 30L;
}


