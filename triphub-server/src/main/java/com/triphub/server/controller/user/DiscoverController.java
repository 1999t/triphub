package com.triphub.server.controller.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.context.BaseContext;
import com.triphub.common.result.Result;
import com.triphub.common.constant.RedisConstants;
import com.triphub.pojo.dto.TripSummaryDTO;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Locale;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;

/**
 * 发现页相关接口：热门行程、热门目的地、个性化推荐行程等。
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
     * 热门行程榜单接口，按 Redis ZSet 分数倒序获取 Top N。
     */
    @GetMapping("/hot-trips")
    public Result<List<TripSummaryDTO>> hotTrips(@RequestParam(defaultValue = "10") int limit,
                                                 @RequestParam(defaultValue = "all") String period) {
        if (limit <= 0) {
            return Result.success(Collections.emptyList());
        }

        Set<String> idSet = stringRedisTemplate.opsForZSet()
                .reverseRange(resolveHotTripKey(period), 0, limit - 1);
        if (idSet == null || idSet.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>();
        for (String s : idSet) {
            try {
                ids.add(Long.valueOf(s));
            } catch (Exception ignore) {
                // ignore bad member
            }
        }
        if (ids.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        // 热榜读减少 DB 压力：优先读摘要缓存 + 叠加 view_count 增量
        List<TripSummaryDTO> ordered = tripService.listPublicTripSummariesForDiscover(ids);
        return Result.success(ordered);
    }

    /**
     * 热门目的地榜单接口，按 Redis ZSet 分数倒序获取 Top N。
     * ZSet 的 member 为目的地城市名（destinationCity），由行程浏览时累计。
     */
    @GetMapping("/hot-destinations")
    public Result<List<String>> hotDestinations(@RequestParam(defaultValue = "10") int limit,
                                                @RequestParam(defaultValue = "all") String period) {
        if (limit <= 0) {
            return Result.success(Collections.emptyList());
        }

        Set<String> citySet = stringRedisTemplate.opsForZSet()
                .reverseRange(resolveHotDestKey(period), 0, limit - 1);
        if (citySet == null || citySet.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        List<String> cities = citySet.stream().collect(Collectors.toList());
        return Result.success(cities);
    }

    /**
     * 为当前用户推荐行程列表接口。
     *
     * 基本思路：
     * - 从热门行程 ZSet 获取一批候选行程；
     * - 过滤仅保留公开行程；
     * - 基于用户画像（tags）做规则打分与可解释排序，形成「热门 + 画像」的轻量推荐结果。
     */
    @GetMapping("/recommend-trips")
    public Result<List<RecommendedTripVO>> recommendTrips(@RequestParam(defaultValue = "10") int limit,
                                                          @RequestParam(defaultValue = "all") String period) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        if (limit <= 0) {
            return Result.success(Collections.emptyList());
        }

        int candidateLimit = Math.min(Math.max(limit * 5, limit), 100);
        Set<String> idSet = stringRedisTemplate.opsForZSet()
                .reverseRange(resolveHotTripKey(period), 0, candidateLimit - 1);
        if (idSet == null || idSet.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>();
        for (String s : idSet) {
            try {
                ids.add(Long.valueOf(s));
            } catch (Exception ignore) {
            }
        }
        if (ids.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        // 加载用户画像，用于生成推荐理由。
        UserProfile profile = userProfileService.getByUserId(userId);
        Map<String, Object> profileMap = parseProfile(profile);
        List<Tag> tags = extractTags(profileMap);

        List<TripSummaryDTO> candidates = tripService.listPublicTripSummariesForDiscover(ids);
        if (candidates.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        // 规则打分：热门程度（viewCount）为底分 + 画像标签命中加分
        List<ScoredTrip> scored = new ArrayList<>(candidates.size());
        for (TripSummaryDTO t : candidates) {
            if (t == null || t.getId() == null) {
                continue;
            }
            int score = scoreTrip(t, tags);
            scored.add(new ScoredTrip(t, score));
        }
        scored.sort(Comparator.comparingInt(ScoredTrip::getScore).reversed());

        List<RecommendedTripVO> result = new ArrayList<>();
        for (int i = 0; i < scored.size() && result.size() < limit; i++) {
            TripSummaryDTO t = scored.get(i).getTrip();
            RecommendedTripVO vo = new RecommendedTripVO();
            vo.setTripId(t.getId());
            vo.setTitle(t.getTitle());
            vo.setDestinationCity(t.getDestinationCity());
            vo.setViewCount(t.getViewCount());
            vo.setReason(buildChineseReason(t, tags, i + 1));
            result.add(vo);
        }

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

    private String resolveHotTripKey(String period) {
        String p = period == null ? "all" : period.trim().toLowerCase(Locale.ROOT);
        if ("day".equals(p)) {
            return RedisConstants.HOT_TRIP_DAY_ZSET_PREFIX + todayYmd();
        }
        if ("week".equals(p)) {
            return RedisConstants.HOT_TRIP_WEEK_ZSET_PREFIX + currentYearWeek();
        }
        return RedisConstants.HOT_TRIP_ZSET;
    }

    private String resolveHotDestKey(String period) {
        String p = period == null ? "all" : period.trim().toLowerCase(Locale.ROOT);
        if ("day".equals(p)) {
            return RedisConstants.HOT_DEST_DAY_ZSET_PREFIX + todayYmd();
        }
        if ("week".equals(p)) {
            return RedisConstants.HOT_DEST_WEEK_ZSET_PREFIX + currentYearWeek();
        }
        return RedisConstants.HOT_DEST_ZSET;
    }

    private static String todayYmd() {
        return LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE); // yyyyMMdd
    }

    private static String currentYearWeek() {
        WeekFields wf = WeekFields.ISO;
        LocalDate now = LocalDate.now();
        int week = now.get(wf.weekOfWeekBasedYear());
        int year = now.get(wf.weekBasedYear());
        return String.format(Locale.ROOT, "%04d%02d", year, week); // YYYYww
    }

    private int scoreTrip(TripSummaryDTO trip, List<Tag> tags) {
        int base = trip.getViewCount() == null ? 0 : trip.getViewCount();
        int score = base;
        if (tags == null || tags.isEmpty()) {
            return score;
        }
        String city = trip.getDestinationCity();
        String title = trip.getTitle();
        for (Tag tag : tags) {
            if (tag == null || tag.name == null || tag.name.isEmpty()) {
                continue;
            }
            int w = Math.max(tag.weight, 0);
            if (city != null && city.equals(tag.name)) {
                score += w * 100;
            } else if (title != null && title.contains(tag.name)) {
                score += w * 10;
            }
        }
        return score;
    }

    private String buildChineseReason(TripSummaryDTO trip, List<Tag> tags, int rank) {
        StringBuilder sb = new StringBuilder();
        sb.append("热门榜单第").append(rank).append("名");
        String hit = bestHitTag(trip, tags);
        if (hit != null) {
            sb.append("，与你偏好「").append(hit).append("」更匹配");
        }
        if (trip.getDestinationCity() != null && !trip.getDestinationCity().isEmpty()) {
            sb.append("，目的地：").append(trip.getDestinationCity());
        }
        return sb.toString();
    }

    private String bestHitTag(TripSummaryDTO trip, List<Tag> tags) {
        if (tags == null || tags.isEmpty() || trip == null) {
            return null;
        }
        String city = trip.getDestinationCity();
        String title = trip.getTitle();
        Tag best = null;
        for (Tag tag : tags) {
            if (tag == null || tag.name == null || tag.name.isEmpty()) {
                continue;
            }
            boolean hit = (city != null && city.equals(tag.name)) || (title != null && title.contains(tag.name));
            if (!hit) {
                continue;
            }
            if (best == null || tag.weight > best.weight) {
                best = tag;
            }
        }
        return best == null ? null : best.name;
    }

    @SuppressWarnings("unchecked")
    private List<Tag> extractTags(Map<String, Object> profileMap) {
        if (profileMap == null) {
            return Collections.emptyList();
        }
        Object tagsObj = profileMap.get("tags");
        if (!(tagsObj instanceof List)) {
            return Collections.emptyList();
        }
        List<Object> list = (List<Object>) tagsObj;
        List<Tag> tags = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            Object nameObj = m.get("name");
            String name = nameObj == null ? null : String.valueOf(nameObj);
            Object weightObj = m.get("weight");
            int weight = weightObj instanceof Number ? ((Number) weightObj).intValue() : 0;
            if (name != null && !name.isEmpty()) {
                tags.add(new Tag(name, weight));
            }
        }
        // weight 降序，便于理由命中时优先选高权重
        tags.sort((a, b) -> Integer.compare(b.weight, a.weight));
        return tags;
    }

    private static final class Tag {
        private final String name;
        private final int weight;

        private Tag(String name, int weight) {
            this.name = name;
            this.weight = weight;
        }
    }

    private static final class ScoredTrip {
        private final TripSummaryDTO trip;
        private final int score;

        private ScoredTrip(TripSummaryDTO trip, int score) {
            this.trip = trip;
            this.score = score;
        }

        private TripSummaryDTO getTrip() {
            return trip;
        }

        private int getScore() {
            return score;
        }
    }
}


