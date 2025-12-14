package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.triphub.common.constant.RedisConstants;
import com.triphub.common.context.BaseContext;
import com.triphub.pojo.dto.TripSummaryDTO;
import com.triphub.pojo.entity.Trip;
import com.triphub.server.mapper.TripMapper;
import com.triphub.server.service.TripService;
import com.triphub.server.utils.CacheClient;
import com.triphub.server.metrics.MetricsRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TripServiceImpl extends ServiceImpl<TripMapper, Trip> implements TripService {

    private final CacheClient cacheClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final MetricsRecorder metricsRecorder;
    private final ObjectMapper objectMapper;

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

        // 抗刷（KISS）：同一用户短时间重复刷新详情不计数
        Long userId = BaseContext.getCurrentId();
        if (userId != null) {
            String dedupKey = RedisConstants.TRIP_VIEW_DEDUP_KEY_PREFIX + userId + ":" + id;
            Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(
                    dedupKey,
                    "1",
                    RedisConstants.TRIP_VIEW_DEDUP_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
            if (!Boolean.TRUE.equals(first)) {
                return;
            }
        }

        // view_count 写放大优化：先在 Redis 记录增量，定时批量刷回 DB（最终一致）
        stringRedisTemplate.opsForHash()
                .increment(RedisConstants.TRIP_VIEW_COUNT_DELTA_HASH, String.valueOf(id), 1L);
        // 给增量 Hash 一个较长 TTL，避免冷数据长期驻留
        stringRedisTemplate.expire(
                RedisConstants.TRIP_VIEW_COUNT_DELTA_HASH,
                RedisConstants.TRIP_VIEW_COUNT_DELTA_TTL_HOURS,
                TimeUnit.HOURS
        );

        // 热榜写入过滤：仅公开行程才进入热门行程/热门目的地榜单（与重建任务的可见性口径一致）
        Integer visibility = trip.getVisibility();
        boolean isPublic = (visibility == null || visibility == 2);
        if (isPublic) {
            // 将行程写入热门行程 ZSet，分数按浏览次数累加
            stringRedisTemplate.opsForZSet()
                    .incrementScore(RedisConstants.HOT_TRIP_ZSET, String.valueOf(id), 1D);
            metricsRecorder.recordHotRankingUpdate("trip");

            // 时间窗口热榜：日榜/周榜（简单替代“时间衰减”，易讲易用）
            String dayKey = RedisConstants.HOT_TRIP_DAY_ZSET_PREFIX + todayYmd();
            String weekKey = RedisConstants.HOT_TRIP_WEEK_ZSET_PREFIX + currentYearWeek();
            stringRedisTemplate.opsForZSet().incrementScore(dayKey, String.valueOf(id), 1D);
            stringRedisTemplate.opsForZSet().incrementScore(weekKey, String.valueOf(id), 1D);
            stringRedisTemplate.expire(dayKey, RedisConstants.HOT_DAY_TTL_DAYS, TimeUnit.DAYS);
            stringRedisTemplate.expire(weekKey, RedisConstants.HOT_WEEK_TTL_DAYS, TimeUnit.DAYS);
        }

        // 让当前响应的 Trip 也体现“本次 +1”（不依赖 DB 落库/缓存刷新）
        Integer vc = trip.getViewCount();
        trip.setViewCount(vc == null ? 1 : vc + 1);

        // 将目的地城市写入热门目的地 ZSet，member 为 destinationCity
        String destCity = trip.getDestinationCity();
        if (isPublic && destCity != null && !destCity.isEmpty()) {
            stringRedisTemplate.opsForZSet()
                    .incrementScore(RedisConstants.HOT_DEST_ZSET, destCity, 1D);
            metricsRecorder.recordHotRankingUpdate("dest");

            String dayKey = RedisConstants.HOT_DEST_DAY_ZSET_PREFIX + todayYmd();
            String weekKey = RedisConstants.HOT_DEST_WEEK_ZSET_PREFIX + currentYearWeek();
            stringRedisTemplate.opsForZSet().incrementScore(dayKey, destCity, 1D);
            stringRedisTemplate.opsForZSet().incrementScore(weekKey, destCity, 1D);
            stringRedisTemplate.expire(dayKey, RedisConstants.HOT_DAY_TTL_DAYS, TimeUnit.DAYS);
            stringRedisTemplate.expire(weekKey, RedisConstants.HOT_WEEK_TTL_DAYS, TimeUnit.DAYS);
        }
    }

    @Override
    public List<TripSummaryDTO> listPublicTripSummariesForDiscover(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return List.of();
        }

        // 1) 先读摘要缓存（减少 DB 压力）
        List<String> keys = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            keys.add(RedisConstants.CACHE_TRIP_SUMMARY_KEY + id);
        }
        List<String> cached = stringRedisTemplate.opsForValue().multiGet(keys);

        Map<Long, TripSummaryDTO> map = new HashMap<>();
        Set<Long> miss = new HashSet<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            Long id = orderedIds.get(i);
            String json = (cached == null || i >= cached.size()) ? null : cached.get(i);
            if (!StringUtils.hasText(json)) {
                miss.add(id);
                continue;
            }
            try {
                TripSummaryDTO dto = objectMapper.readValue(json, TripSummaryDTO.class);
                if (dto != null && dto.getId() != null) {
                    map.put(dto.getId(), dto);
                } else {
                    miss.add(id);
                }
            } catch (Exception e) {
                miss.add(id);
            }
        }

        // 2) 缓存未命中回源 DB（只取公开行程）
        if (!miss.isEmpty()) {
            List<Trip> trips = listByIds(miss);
            if (trips != null && !trips.isEmpty()) {
                for (Trip t : trips) {
                    if (t == null || t.getId() == null) {
                        continue;
                    }
                    Integer v = t.getVisibility();
                    boolean isPublic = (v == null || v == 2);
                    if (!isPublic) {
                        continue;
                    }
                    TripSummaryDTO dto = toSummary(t);
                    map.put(dto.getId(), dto);
                    try {
                        String json = objectMapper.writeValueAsString(dto);
                        stringRedisTemplate.opsForValue().set(
                                RedisConstants.CACHE_TRIP_SUMMARY_KEY + dto.getId(),
                                json,
                                RedisConstants.CACHE_TRIP_SUMMARY_TTL_MINUTES,
                                TimeUnit.MINUTES
                        );
                    } catch (Exception ignore) {
                        // ignore cache set
                    }
                }
            }
        }

        // 3) 叠加尚未落库的 view_count 增量（展示更实时）
        List<Object> fields = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            fields.add(String.valueOf(id));
        }
        List<Object> deltaList = stringRedisTemplate.opsForHash()
                .multiGet(RedisConstants.TRIP_VIEW_COUNT_DELTA_HASH, fields);
        if (deltaList != null && !deltaList.isEmpty()) {
            for (int i = 0; i < orderedIds.size() && i < deltaList.size(); i++) {
                Long id = orderedIds.get(i);
                TripSummaryDTO dto = map.get(id);
                if (dto == null) {
                    continue;
                }
                Long delta = parseLong(deltaList.get(i));
                if (delta == null || delta <= 0) {
                    continue;
                }
                Integer base = dto.getViewCount() == null ? 0 : dto.getViewCount();
                long merged = base.longValue() + delta;
                dto.setViewCount(merged > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) merged);
            }
        }

        // 4) 按输入 id 顺序输出
        List<TripSummaryDTO> result = new ArrayList<>();
        for (Long id : orderedIds) {
            TripSummaryDTO dto = map.get(id);
            if (dto != null) {
                result.add(dto);
            }
        }
        return result;
    }

    private TripSummaryDTO toSummary(Trip t) {
        TripSummaryDTO dto = new TripSummaryDTO();
        dto.setId(t.getId());
        dto.setTitle(t.getTitle());
        dto.setDestinationCity(t.getDestinationCity());
        dto.setStartDate(t.getStartDate());
        dto.setEndDate(t.getEndDate());
        dto.setDays(t.getDays());
        dto.setViewCount(t.getViewCount());
        dto.setLikeCount(t.getLikeCount());
        return dto;
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


