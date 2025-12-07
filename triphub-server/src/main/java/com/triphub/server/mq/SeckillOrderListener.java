package com.triphub.server.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.triphub.common.constant.RedisConstants;
import com.triphub.pojo.entity.Order;
import com.triphub.pojo.entity.SeckillActivity;
import com.triphub.server.config.MqConfig;
import com.triphub.server.service.OrderService;
import com.triphub.server.service.SeckillActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单异步消费者：
 * - 从 MQ 中消费预检通过的秒杀订单消息；
 * - 通过 Redisson 分布式锁和数据库二次检查保证一人一单和幂等；
 * - 在数据库中创建订单，并尽力扣减活动库存。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillOrderListener {

    private final OrderService orderService;
    private final SeckillActivityService seckillActivityService;
    private final RedissonClient redissonClient;

    @RabbitListener(queues = MqConfig.SECKILL_QUEUE)
    public void handleSeckillOrder(Map<String, Object> payload) {
        Long orderId = toLong(payload.get("orderId"));
        Long userId = toLong(payload.get("userId"));
        Long activityId = toLong(payload.get("activityId"));

        if (orderId == null || userId == null || activityId == null) {
            log.warn("非法的秒杀订单消息: {}", payload);
            return;
        }

        // 每个用户维度加一把分布式锁，防止同一用户消息并发消费
        String lockKey = RedisConstants.LOCK_SECKILL_ORDER + userId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            // 尝试获取锁，避免消费者长期阻塞
            locked = lock.tryLock(1, 5, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取秒杀订单分布式锁失败, userId={}, activityId={}", userId, activityId);
                return;
            }

            // 再次检查该用户在该活动下是否已存在订单（防止重复落单）
            long count = orderService.count(new LambdaQueryWrapper<Order>()
                    .eq(Order::getUserId, userId)
                    .eq(Order::getSeckillActivityId, activityId));
            if (count > 0) {
                log.info("用户已存在该活动订单, userId={}, activityId={}", userId, activityId);
                return;
            }

            // 加载活动信息（可用于后续根据活动配置计算金额等）
            SeckillActivity activity = seckillActivityService.getById(activityId);
            if (activity == null) {
                log.warn("秒杀活动不存在, activityId={}", activityId);
                return;
            }

            Order order = new Order();
            order.setId(orderId);
            order.setUserId(userId);
            order.setSeckillActivityId(activityId);
            order.setStatus(0);
            order.setOrderTime(LocalDateTime.now());
            // 这里为了简单演示，金额先写 0，真实项目应根据活动价格计算
            order.setAmount(BigDecimal.ZERO);
            orderService.save(order);

            // 尽力而为地扣减数据库库存，配合 Redis 库存实现最终一致
            seckillActivityService.lambdaUpdate()
                    .setSql("stock = stock - 1")
                    .eq(SeckillActivity::getId, activityId)
                    .gt(SeckillActivity::getStock, 0)
                    .update();
        } catch (Exception e) {
            log.error("处理秒杀订单消息失败", e);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}


