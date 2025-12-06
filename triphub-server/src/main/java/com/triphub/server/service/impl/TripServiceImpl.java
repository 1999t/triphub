package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.triphub.common.constant.RedisConstants;
import com.triphub.pojo.entity.Trip;
import com.triphub.server.mapper.TripMapper;
import com.triphub.server.service.TripService;
import com.triphub.server.utils.CacheClient;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TripServiceImpl extends ServiceImpl<TripMapper, Trip> implements TripService {

    private final CacheClient cacheClient;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Trip queryTripById(Long id) {
        return cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_TRIP_KEY,
                id,
                Trip.class,
                this::getById,
                RedisConstants.CACHE_TRIP_TTL,
                TimeUnit.MINUTES,
                RedisConstants.LOCK_TRIP_KEY
        );
    }

    @Override
    public void increaseViewCountAndHotScore(Trip trip) {
        if (trip == null || trip.getId() == null) {
            return;
        }
        Long id = trip.getId();

        // Simple incremental update for view_count
        this.update()
                .setSql("view_count = view_count + 1")
                .eq("id", id)
                .update();

        // Write into hot trips ZSet, score accumulates by view count
        stringRedisTemplate.opsForZSet()
                .incrementScore(RedisConstants.HOT_TRIP_ZSET, String.valueOf(id), 1D);

        // Write into hot destinations ZSet using destinationCity as member
        String destCity = trip.getDestinationCity();
        if (destCity != null && !destCity.isEmpty()) {
            stringRedisTemplate.opsForZSet()
                    .incrementScore(RedisConstants.HOT_DEST_ZSET, destCity, 1D);
        }
    }
}


