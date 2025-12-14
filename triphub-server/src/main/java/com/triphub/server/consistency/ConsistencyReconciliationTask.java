package com.triphub.server.consistency;

import com.triphub.common.constant.RedisConstants;
import com.triphub.pojo.entity.Trip;
import com.triphub.server.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 热门行程 / 热门目的地榜单的一致性重建任务。
 *
 * <p>职责：
 * <ul>
 *     <li>周期性从 DB 的 view_count 重建热门行程 / 热门目的地 ZSet，</li>
 *     <li>支持 Redis 丢数据或被清空后自动恢复排行榜。</li>
 * </ul>
 * 该组件永远不修改 DB，只基于 DB 作为单一事实源（single source of truth）去修正 Redis。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsistencyReconciliationTask {

    private final StringRedisTemplate stringRedisTemplate;
    private final TripService tripService;

    /**
     * 定期基于 DB 的 view_count 重建热门行程 / 热门目的地 ZSet，
     * 支持在 Redis 丢数据或被清空后自动恢复排行榜。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void rebuildHotRankingFromDb() {
        int limit = 100;

        @SuppressWarnings({"unchecked", "varargs"})
        List<Trip> trips = tripService.lambdaQuery()
                .eq(Trip::getVisibility, 2)
                .orderByDesc(Trip::getViewCount)
                .last("limit " + limit)
                .list();

        if (trips == null || trips.isEmpty()) {
            // 无数据时直接清空线上榜单即可
            stringRedisTemplate.delete(RedisConstants.HOT_TRIP_ZSET);
            stringRedisTemplate.delete(RedisConstants.HOT_DEST_ZSET);
            log.info("重建热门榜单完成: 当前没有公开行程数据，已清空榜单");
            return;
        }

        // 原子切换：先写入临时 key，重建完毕后用 RENAME 覆盖线上 key，避免 DEL 导致的空窗
        String suffix = String.valueOf(System.currentTimeMillis());
        String tmpTripKey = RedisConstants.HOT_TRIP_ZSET + ":tmp:" + suffix;
        String tmpDestKey = RedisConstants.HOT_DEST_ZSET + ":tmp:" + suffix;
        // 清理可能遗留的临时 key（理论上不会重名，但以防万一）
        stringRedisTemplate.delete(tmpTripKey);
        stringRedisTemplate.delete(tmpDestKey);

        boolean wroteDest = false;
        for (Trip trip : trips) {
            if (trip == null || trip.getId() == null) {
                continue;
            }
            Long tripId = trip.getId();
            int viewCount = trip.getViewCount() == null ? 0 : trip.getViewCount();

            // hot:trip 使用 tripId 作为 member，view_count 作为 score。
            stringRedisTemplate.opsForZSet().add(
                    tmpTripKey,
                    String.valueOf(tripId),
                    viewCount
            );

            // hot:dest 使用 destinationCity 作为 member，score 为该城市下所有行程 view_count 的累计值。
            String destCity = trip.getDestinationCity();
            if (destCity != null && !destCity.isEmpty()) {
                double score = viewCount > 0 ? viewCount : 1D;
                stringRedisTemplate.opsForZSet().incrementScore(
                        tmpDestKey,
                        destCity,
                        score
                );
                wroteDest = true;
            }
        }

        // 覆盖线上 key（RENAME 原子切换）
        try {
            stringRedisTemplate.rename(tmpTripKey, RedisConstants.HOT_TRIP_ZSET);
        } catch (Exception e) {
            // rename 失败时回退：至少保证线上 key 不会被我们清空
            log.warn("重建热门行程榜单切换失败, tmpKey={}", tmpTripKey, e);
        }

        if (wroteDest) {
            try {
                stringRedisTemplate.rename(tmpDestKey, RedisConstants.HOT_DEST_ZSET);
            } catch (Exception e) {
                log.warn("重建热门目的地榜单切换失败, tmpKey={}", tmpDestKey, e);
            }
        } else {
            // 没有任何有效城市写入时，目的地榜单应为空
            stringRedisTemplate.delete(RedisConstants.HOT_DEST_ZSET);
            stringRedisTemplate.delete(tmpDestKey);
        }

        log.info("基于 DB 成功重建热门行程 / 热门目的地 ZSet, tripCount={}", trips.size());
    }
}


