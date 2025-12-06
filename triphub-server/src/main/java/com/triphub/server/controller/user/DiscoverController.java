package com.triphub.server.controller.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.context.BaseContext;
import com.triphub.common.result.Result;
import com.triphub.common.constant.RedisConstants;
import com.triphub.pojo.entity.Trip;
import com.triphub.pojo.entity.UserProfile;
import com.triphub.pojo.vo.RecommendedTripVO;
import com.triphub.server.service.TripService;
import com.triphub.server.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Discover page APIs: hot trips, etc.
 */
@RestController
@RequestMapping("/user/discover")
@RequiredArgsConstructor
public class DiscoverController {

    private final StringRedisTemplate stringRedisTemplate;
    private final TripService tripService;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;

    /**
     * Hot trips leaderboard, ordered by Redis ZSet score (top N).
     */
    @GetMapping("/hot-trips")
    public Result<List<Trip>> hotTrips(@RequestParam(defaultValue = "10") int limit) {
        if (limit <= 0) {
            return Result.success(Collections.emptyList());
        }

        Set<String> idSet = stringRedisTemplate.opsForZSet()
                .reverseRange(RedisConstants.HOT_TRIP_ZSET, 0, limit - 1);
        if (idSet == null || idSet.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        List<Long> ids = idSet.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        List<Trip> trips = tripService.listByIds(ids);
        if (trips.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        Map<Long, Trip> tripMap = trips.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Trip::getId, t -> t));

        // Keep ZSet order and filter invisible trips (only public trips)
        List<Trip> ordered = ids.stream()
                .map(tripMap::get)
                .filter(Objects::nonNull)
                .filter(t -> t.getVisibility() == null || t.getVisibility() == 2)
                .collect(Collectors.toList());

        return Result.success(ordered);
    }

    /**
     * Hot destinations leaderboard, ordered by Redis ZSet score (top N).
     * Member is destinationCity string aggregated from trip views.
     */
    @GetMapping("/hot-destinations")
    public Result<List<String>> hotDestinations(@RequestParam(defaultValue = "10") int limit) {
        if (limit <= 0) {
            return Result.success(Collections.emptyList());
        }

        Set<String> citySet = stringRedisTemplate.opsForZSet()
                .reverseRange(RedisConstants.HOT_DEST_ZSET, 0, limit - 1);
        if (citySet == null || citySet.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        List<String> cities = citySet.stream().collect(Collectors.toList());
        return Result.success(cities);
    }

    /**
     * Personalized recommended trips for current user based on hot leaderboard and simple profile.
     */
    @GetMapping("/recommend-trips")
    public Result<List<RecommendedTripVO>> recommendTrips(@RequestParam(defaultValue = "10") int limit) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        if (limit <= 0) {
            return Result.success(Collections.emptyList());
        }

        Set<String> idSet = stringRedisTemplate.opsForZSet()
                .reverseRange(RedisConstants.HOT_TRIP_ZSET, 0, limit - 1);
        if (idSet == null || idSet.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        List<Long> ids = idSet.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        List<Trip> trips = tripService.listByIds(ids);
        if (trips.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        Map<Long, Trip> tripMap = trips.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Trip::getId, t -> t));

        // Load user profile for explanation.
        UserProfile profile = userProfileService.getByUserId(userId);
        Map<String, Object> profileMap = parseProfile(profile);

        List<RecommendedTripVO> result = ids.stream()
                .map(tripMap::get)
                .filter(Objects::nonNull)
                .filter(t -> t.getVisibility() == null || t.getVisibility() == 2)
                .map(t -> buildRecommendedTripVO(t, profileMap))
                .collect(Collectors.toList());

        return Result.success(result);
    }

    private Map<String, Object> parseProfile(UserProfile profile) {
        if (profile == null || profile.getProfileJson() == null || profile.getProfileJson().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(profile.getProfileJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private RecommendedTripVO buildRecommendedTripVO(Trip trip, Map<String, Object> profileMap) {
        RecommendedTripVO vo = new RecommendedTripVO();
        vo.setTripId(trip.getId());
        vo.setTitle(trip.getTitle());
        vo.setDestinationCity(trip.getDestinationCity());
        vo.setViewCount(trip.getViewCount());
        vo.setReason(buildReason(trip, profileMap));
        return vo;
    }

    private String buildReason(Trip trip, Map<String, Object> profileMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("This trip is popular in the current leaderboard");
        String topTagName = extractTopTagName(profileMap);
        if (topTagName != null && !topTagName.isEmpty()) {
            sb.append(" and matches your interest in ").append(topTagName);
        }
        if (trip.getDestinationCity() != null && !trip.getDestinationCity().isEmpty()) {
            sb.append(". Destination: ").append(trip.getDestinationCity()).append(".");
        } else {
            sb.append(".");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractTopTagName(Map<String, Object> profileMap) {
        if (profileMap == null) {
            return null;
        }
        Object tagsObj = profileMap.get("tags");
        if (!(tagsObj instanceof List)) {
            return null;
        }
        List<Object> tags = (List<Object>) tagsObj;
        String bestName = null;
        int bestWeight = -1;
        for (Object t : tags) {
            if (!(t instanceof Map)) {
                continue;
            }
            Map<String, Object> tag = (Map<String, Object>) t;
            Object weightObj = tag.get("weight");
            int weight = weightObj instanceof Number ? ((Number) weightObj).intValue() : 0;
            if (weight > bestWeight) {
                bestWeight = weight;
                Object nameObj = tag.get("name");
                bestName = nameObj == null ? null : String.valueOf(nameObj);
            }
        }
        return bestName;
    }
}


