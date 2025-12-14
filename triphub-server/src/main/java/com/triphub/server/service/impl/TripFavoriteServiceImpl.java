package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.constant.RedisConstants;
import com.triphub.pojo.entity.Trip;
import com.triphub.pojo.entity.TripFavorite;
import com.triphub.pojo.entity.UserProfile;
import com.triphub.server.mapper.TripFavoriteMapper;
import com.triphub.server.service.TripFavoriteService;
import com.triphub.server.service.TripService;
import com.triphub.server.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 行程收藏服务实现。
 *
 * 约定：
 * - addFavorite / removeFavorite 都是幂等操作；
 * - 每次收藏变更后会：
 *   1）更新 Trip.like_count 计数；
 *   2）重算该用户画像中的 stats.totalTripsFavorited / stats.topCitiesByFavorite。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TripFavoriteServiceImpl extends ServiceImpl<TripFavoriteMapper, TripFavorite> implements TripFavoriteService {

    private final TripService tripService;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 收藏 stats 重建是 O(n) 的扫描操作，放到异步线程里避免阻塞主请求。
     * 这里保持 KISS：最终一致即可。
     */
    private static final ExecutorService FAVORITE_STATS_EXECUTOR = Executors.newFixedThreadPool(2);

    @Override
    public boolean addFavorite(Long userId, Long tripId) {
        if (userId == null || tripId == null) {
            return false;
        }
        TripFavorite existing = lambdaQuery()
                .eq(TripFavorite::getUserId, userId)
                .eq(TripFavorite::getTripId, tripId)
                .one();
        if (existing != null) {
            return true;
        }
        TripFavorite favorite = new TripFavorite();
        favorite.setUserId(userId);
        favorite.setTripId(tripId);
        boolean saved = save(favorite);
        if (!saved) {
            return false;
        }
        // 更新 Trip.like_count
        tripService.update()
                .setSql("like_count = like_count + 1")
                .eq("id", tripId)
                .update();

        // 写后删缓存：like_count 会影响 Trip 详情展示（如果详情包含 likeCount）
        stringRedisTemplate.delete(RedisConstants.CACHE_TRIP_KEY + tripId);

        // 重算画像统计字段（异步，最终一致）
        rebuildProfileStatsAsync(userId);
        return true;
    }

    @Override
    public boolean removeFavorite(Long userId, Long tripId) {
        if (userId == null || tripId == null) {
            return false;
        }
        TripFavorite existing = lambdaQuery()
                .eq(TripFavorite::getUserId, userId)
                .eq(TripFavorite::getTripId, tripId)
                .one();
        if (existing == null) {
            return true;
        }
        boolean removed = removeById(existing.getId());
        if (!removed) {
            return false;
        }
        // like_count 简单做减 1，最低不小于 0
        tripService.update()
                .setSql("like_count = CASE WHEN like_count > 0 THEN like_count - 1 ELSE 0 END")
                .eq("id", tripId)
                .update();

        // 写后删缓存：like_count 变化后失效详情缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_TRIP_KEY + tripId);

        rebuildProfileStatsAsync(userId);
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<TripFavorite> listRecentFavorites(Long userId, int limit) {
        if (userId == null || limit <= 0) {
            return Collections.emptyList();
        }
        return lambdaQuery()
                .eq(TripFavorite::getUserId, userId)
                .orderByDesc(TripFavorite::getCreateTime)
                .last("LIMIT " + limit)
                .list();
    }

    /**
     * 基于 trip_favorite 与 trip 表，重算指定用户的收藏统计信息，
     * 并写回 user_profile.profile_json.stats。
     */
    private void rebuildProfileStats(Long userId) {
        try {
            List<TripFavorite> favorites = lambdaQuery()
                    .eq(TripFavorite::getUserId, userId)
                    .list();
            int total = favorites.size();

            // 查询所有收藏的行程，统计目的地城市热度
            Map<Long, Long> tripIdCountMap = favorites.stream()
                    .collect(Collectors.groupingBy(TripFavorite::getTripId, Collectors.counting()));
            List<Long> tripIds = new ArrayList<>(tripIdCountMap.keySet());
            List<Trip> trips = tripIds.isEmpty() ? Collections.emptyList() : tripService.listByIds(tripIds);
            Map<Long, Trip> tripMap = trips.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(Trip::getId, t -> t));

            Map<String, Long> cityCount = new HashMap<>();
            for (Map.Entry<Long, Long> entry : tripIdCountMap.entrySet()) {
                Trip trip = tripMap.get(entry.getKey());
                if (trip == null) {
                    continue;
                }
                String city = trip.getDestinationCity();
                if (city == null || city.isEmpty()) {
                    continue;
                }
                cityCount.merge(city, entry.getValue(), Long::sum);
            }

            List<String> topCities = cityCount.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(3)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            // 读取或创建用户画像 JSON
            UserProfile profile = userProfileService.getByUserId(userId);
            Map<String, Object> profileMap;
            if (profile == null || profile.getProfileJson() == null || profile.getProfileJson().isEmpty()) {
                profileMap = new HashMap<>();
            } else {
                profileMap = objectMapper.readValue(profile.getProfileJson(), new TypeReference<Map<String, Object>>() {});
            }

            Map<String, Object> statsMap = getOrCreateStatsMap(profileMap);
            statsMap.put("totalTripsFavorited", total);
            if (!CollectionUtils.isEmpty(topCities)) {
                statsMap.put("topCitiesByFavorite", topCities);
            } else {
                statsMap.remove("topCitiesByFavorite");
            }
            if (statsMap.isEmpty()) {
                profileMap.remove("stats");
            } else {
                profileMap.put("stats", statsMap);
            }

            String newJson = objectMapper.writeValueAsString(profileMap);
            userProfileService.saveOrUpdateProfile(userId, newJson);
        } catch (Exception e) {
            // 统计失败不应该影响主业务逻辑，打印日志即可
            log.warn("重建用户收藏画像统计失败, userId={}", userId, e);
        }
    }

    private void rebuildProfileStatsAsync(Long userId) {
        if (userId == null) {
            return;
        }
        FAVORITE_STATS_EXECUTOR.submit(() -> rebuildProfileStats(userId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOrCreateStatsMap(Map<String, Object> profileMap) {
        Object statsObj = profileMap.get("stats");
        if (statsObj instanceof Map) {
            return new HashMap<>((Map<String, Object>) statsObj);
        }
        return new HashMap<>();
    }
}



