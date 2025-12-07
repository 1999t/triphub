package com.triphub.server.consistency;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.triphub.common.constant.RedisConstants;
import com.triphub.pojo.entity.Order;
import com.triphub.pojo.entity.SeckillActivity;
import com.triphub.pojo.entity.Trip;
import com.triphub.server.service.OrderService;
import com.triphub.server.service.SeckillActivityService;
import com.triphub.server.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 秒杀与热门榜单的一致性校对与自愈任务。
 *
 * <p>职责：
 * <ul>
 *     <li>周期性对比 Redis 中的秒杀库存 / 下单用户 与 DB 中的真实数据；</li>
 *     <li>在检测到不一致且 DB 领先 Redis 时，基于 DB 反向重建秒杀相关 Redis Key，实现缓存自愈；</li>
 *     <li>周期性从 DB 的 view_count 重建热门行程 / 热门目的地 ZSet，支持 Redis 丢数据后的自动恢复。</li>
 * </ul>
 * 该组件永远不修改 DB，只基于 DB 作为单一事实源（single source of truth）去修正 Redis。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsistencyReconciliationTask {

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillActivityService seckillActivityService;
    private final OrderService orderService;
    private final TripService tripService;

    /**
     * 周期性对比秒杀库存与一人一单集合在 Redis 和 DB 之间的一致性，
     * 如发现不一致且 DB 领先 Redis，则对 Redis 做自动补偿。
     */
    @Scheduled(fixedDelay = 300000L, initialDelay = 300000L)
    public void checkSeckillConsistency() {
        // 只对在线的秒杀活动做对账，避免全表扫描带来的不必要开销。
        List<SeckillActivity> activities = seckillActivityService.lambdaQuery()
                .eq(SeckillActivity::getStatus, 1)
                .list();
        if (activities == null || activities.isEmpty()) {
            return;
        }

        for (SeckillActivity activity : activities) {
            if (activity == null || activity.getId() == null) {
                continue;
            }
            Long activityId = activity.getId();

            String stockKey = RedisConstants.SECKILL_STOCK_KEY + activityId;
            String orderKey = RedisConstants.SECKILL_ORDER_KEY + activityId;

            Integer dbStock = activity.getStock();
            String redisStockStr = stringRedisTemplate.opsForValue().get(stockKey);
            Integer redisStock = parseInteger(redisStockStr);

            long dbOrderCount = orderService.count(new LambdaQueryWrapper<Order>()
                    .eq(Order::getSeckillActivityId, activityId));
            Long redisOrderCount = stringRedisTemplate.opsForSet().size(orderKey);
            long redisOrderSize = redisOrderCount == null ? 0L : redisOrderCount;

            // 只要存在不一致，就先打日志，方便观测和排查。
            boolean stockMismatch = (redisStock != null && dbStock != null && !redisStock.equals(dbStock))
                    || (redisStock == null && dbStock != null && dbStock > 0)
                    || (redisStock != null && (dbStock == null || dbStock < 0));
            boolean orderCountMismatch = redisOrderSize != dbOrderCount;

            if (stockMismatch || orderCountMismatch) {
                log.warn(
                        "秒杀一致性校验发现不一致: activityId={}, redisStock={}, dbStock={}, redisOrderSize={}, dbOrderCount={}",
                        activityId, redisStock, dbStock, redisOrderSize, dbOrderCount
                );
            }

            // 仅在「DB 领先 Redis」的场景下做补偿，避免干扰正常「先改 Redis、后落 DB」的异步链路。
            boolean needCompensation =
                    // Redis 库存大于 DB 库存 -> 存在超卖风险，需要“收紧” Redis。
                    (redisStock != null && dbStock != null && redisStock > dbStock)
                            // Redis 库存 Key 缺失但 DB 仍有可售库存。
                            || (redisStock == null && dbStock != null && dbStock > 0)
                            // Redis 一人一单集合中的用户数小于 DB 订单条数 -> 有用户从 Redis Set 中“丢失”。
                            || (redisOrderSize < dbOrderCount);

            if (needCompensation) {
                rebuildSeckillCacheFromDb(activityId);
            } else if (!stockMismatch && !orderCountMismatch) {
                log.debug(
                        "秒杀一致性校验通过: activityId={}, stock={}, orderCount={}",
                        activityId, dbStock, dbOrderCount
                );
            }
        }
    }

    /**
     * 使用 DB 作为事实源，重建指定活动的秒杀相关 Redis Key。
     *
     * <p>策略：
     * <ul>
     *     <li>库存：将 {@code seckill:stock:{id}} 直接设置为 {@code seckill_activity.stock}；</li>
     *     <li>一人一单集合：清空 {@code seckill:order:{id}}，再根据 {@code order} 表中
     *     {@code seckill_activity_id = id} 的记录，将所有 userId 加入集合。</li>
     * </ul>
     * 该操作是幂等的，可以安全地重复执行。
     */
    private void rebuildSeckillCacheFromDb(Long activityId) {
        if (activityId == null) {
            return;
        }
        SeckillActivity latest = seckillActivityService.getById(activityId);
        if (latest == null) {
            return;
        }

        String stockKey = RedisConstants.SECKILL_STOCK_KEY + activityId;
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + activityId;

        Integer dbStock = latest.getStock();
        if (dbStock != null && dbStock >= 0) {
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(dbStock));
        } else {
            // 若 DB 库存为空或为负，直接删除 Redis Key，具体含义交由后续业务决定。
            stringRedisTemplate.delete(stockKey);
        }

        // 根据 DB 订单记录重建 seckill:order:{id}，以 DB 为准保证一人一单。
        List<Order> orders = orderService.list(new LambdaQueryWrapper<Order>()
                .select(Order::getUserId)
                .eq(Order::getSeckillActivityId, activityId));

        stringRedisTemplate.delete(orderKey);
        long userCount = 0L;
        if (orders != null && !orders.isEmpty()) {
            for (Order order : orders) {
                if (order == null || order.getUserId() == null) {
                    continue;
                }
                stringRedisTemplate.opsForSet()
                        .add(orderKey, String.valueOf(order.getUserId()));
                userCount++;
            }
        }

        log.info(
                "秒杀一致性自动补偿完成: activityId={}, dbStock={}, rebuiltUserCount={}",
                activityId, dbStock, userCount
        );
    }

    private Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            log.warn("解析 Redis 库存值失败, value={}", value);
            return null;
        }
    }

    /**
     * 定期基于 DB 的 view_count 重建热门行程 / 热门目的地 ZSet，
     * 支持在 Redis 丢数据或被清空后自动恢复排行榜。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void rebuildHotRankingFromDb() {
        int limit = 100;

        List<Trip> trips = tripService.lambdaQuery()
                .eq(Trip::getVisibility, 2)
                .orderByDesc(Trip::getViewCount)
                .last("limit " + limit)
                .list();

        // 先清空原有 ZSet，保证本次是完整重建。
        stringRedisTemplate.delete(RedisConstants.HOT_TRIP_ZSET);
        stringRedisTemplate.delete(RedisConstants.HOT_DEST_ZSET);

        if (trips == null || trips.isEmpty()) {
            log.info("重建热门榜单跳过: 当前没有公开行程数据");
            return;
        }

        for (Trip trip : trips) {
            if (trip == null || trip.getId() == null) {
                continue;
            }
            Long tripId = trip.getId();
            int viewCount = trip.getViewCount() == null ? 0 : trip.getViewCount();

            // hot:trip 使用 tripId 作为 member，view_count 作为 score。
            stringRedisTemplate.opsForZSet().add(
                    RedisConstants.HOT_TRIP_ZSET,
                    String.valueOf(tripId),
                    viewCount
            );

            // hot:dest 使用 destinationCity 作为 member，score 为该城市下所有行程 view_count 的累计值。
            String destCity = trip.getDestinationCity();
            if (destCity != null && !destCity.isEmpty()) {
                double score = viewCount > 0 ? viewCount : 1D;
                stringRedisTemplate.opsForZSet().incrementScore(
                        RedisConstants.HOT_DEST_ZSET,
                        destCity,
                        score
                );
            }
        }

        log.info("基于 DB 成功重建热门行程 / 热门目的地 ZSet, tripCount={}", trips.size());
    }
}


