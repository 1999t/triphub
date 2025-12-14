package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.triphub.common.constant.RedisConstants;
import com.triphub.pojo.entity.Trip;
import com.triphub.server.mapper.TripMapper;
import com.triphub.server.service.TripService;
import com.triphub.server.utils.CacheClient;
import com.triphub.server.metrics.MetricsRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TripServiceImpl extends ServiceImpl<TripMapper, Trip> implements TripService {

    private final CacheClient cacheClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final MetricsRecorder metricsRecorder;

    @Override
    public Trip queryTripById(Long id) {
        Trip trip = cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_TRIP_KEY,
                id,
                Trip.class,
                this::getById,
                RedisConstants.CACHE_TRIP_TTL,
                TimeUnit.MINUTES,
                RedisConstants.LOCK_TRIP_KEY
        );
        // view_count 动态字段：把 Redis 中尚未落库的增量叠加到返回值，避免详情长期展示旧浏览量
        if (trip != null && trip.getId() != null) {
            Object deltaObj = stringRedisTemplate.opsForHash()
                    .get(RedisConstants.TRIP_VIEW_COUNT_DELTA_HASH, String.valueOf(trip.getId()));
            Long delta = parseLong(deltaObj);
            if (delta != null && delta > 0) {
                Integer base = trip.getViewCount() == null ? 0 : trip.getViewCount();
                long merged = base.longValue() + delta;
                trip.setViewCount(merged > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) merged);
            }
        }
        return trip;
    }

    @Override
    public void increaseViewCountAndHotScore(Trip trip) {
        if (trip == null || trip.getId() == null) {
            return;
        }
        Long id = trip.getId();

        // view_count 写放大优化：先在 Redis 记录增量，定时批量刷回 DB（最终一致）
        stringRedisTemplate.opsForHash()
                .increment(RedisConstants.TRIP_VIEW_COUNT_DELTA_HASH, String.valueOf(id), 1L);
        // 给增量 Hash 一个较长 TTL，避免冷数据长期驻留
        stringRedisTemplate.expire(
                RedisConstants.TRIP_VIEW_COUNT_DELTA_HASH,
                RedisConstants.TRIP_VIEW_COUNT_DELTA_TTL_HOURS,
                TimeUnit.HOURS
        );

        // 将行程写入热门行程 ZSet，分数按浏览次数累加
        stringRedisTemplate.opsForZSet()
                .incrementScore(RedisConstants.HOT_TRIP_ZSET, String.valueOf(id), 1D);
        metricsRecorder.recordHotRankingUpdate("trip");

        // 让当前响应的 Trip 也体现“本次 +1”（不依赖 DB 落库/缓存刷新）
        Integer vc = trip.getViewCount();
        trip.setViewCount(vc == null ? 1 : vc + 1);

        // 将目的地城市写入热门目的地 ZSet，member 为 destinationCity
        String destCity = trip.getDestinationCity();
        if (destCity != null && !destCity.isEmpty()) {
            stringRedisTemplate.opsForZSet()
                    .incrementScore(RedisConstants.HOT_DEST_ZSET, destCity, 1D);
            metricsRecorder.recordHotRankingUpdate("dest");
        }
    }

    private Long parseLong(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(obj));
        } catch (Exception e) {
            return null;
        }
    }
}


