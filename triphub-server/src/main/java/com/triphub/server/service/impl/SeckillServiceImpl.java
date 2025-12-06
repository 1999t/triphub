package com.triphub.server.service.impl;

import com.triphub.common.context.BaseContext;
import com.triphub.common.constant.RedisConstants;
import com.triphub.common.result.Result;
import com.triphub.pojo.entity.SeckillActivity;
import com.triphub.server.config.MqConfig;
import com.triphub.server.service.SeckillActivityService;
import com.triphub.server.service.SeckillService;
import com.triphub.server.metrics.MetricsRecorder;
import com.triphub.server.utils.RedisIdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 秒杀核心业务：
 * 1）校验活动是否可用及时间窗口是否合法；
 * 2）通过 Redis + Lua 原子预检库存和一人一单；
 * 3）使用 RedisIdWorker 生成全局唯一订单号；
 * 4）将下单消息投递到 MQ，由异步消费者真正落库。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeckillServiceImpl implements SeckillService {

    private final SeckillActivityService seckillActivityService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisIdWorker redisIdWorker;
    private final RabbitTemplate rabbitTemplate;
    private final MetricsRecorder metricsRecorder;

    private static final String SECKILL_LUA;

    static {
        // 0: 成功；1: 库存不足；2: 重复下单（一人一单限制）。
        SECKILL_LUA = ""
                + "local stockKey = KEYS[1]\n"
                + "local orderKey = KEYS[2]\n"
                + "local userId = ARGV[1]\n"
                + "local stock = tonumber(redis.call('GET', stockKey))\n"
                + "if not stock or stock <= 0 then\n"
                + "  return 1\n"
                + "end\n"
                + "if redis.call('SISMEMBER', orderKey, userId) == 1 then\n"
                + "  return 2\n"
                + "end\n"
                + "redis.call('DECR', stockKey)\n"
                + "redis.call('SADD', orderKey, userId)\n"
                + "return 0\n";
    }

    @Override
    public Long seckill(Long activityId) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new IllegalStateException("User not logged in");
        }
        if (activityId == null) {
            throw new IllegalArgumentException("Activity ID is required");
        }

        // 1. 校验活动基础信息和时间窗口（状态、开始时间、结束时间）
        SeckillActivity activity = seckillActivityService.getById(activityId);
        if (activity == null || activity.getStatus() == null || activity.getStatus() != 1) {
            throw new IllegalStateException("Seckill activity not available");
        }
        LocalDateTime now = LocalDateTime.now();
        if (activity.getBeginTime() != null && now.isBefore(activity.getBeginTime())) {
            throw new IllegalStateException("Seckill has not started");
        }
        if (activity.getEndTime() != null && now.isAfter(activity.getEndTime())) {
            throw new IllegalStateException("Seckill has ended");
        }

        // 2. 执行 Lua 脚本，原子完成库存校验 + 扣减，以及一人一单校验
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + activityId;
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + activityId;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(SECKILL_LUA);

        Long result = stringRedisTemplate.execute(
                script,
                Arrays.asList(stockKey, orderKey),
                userId.toString()
        );

        if (result == null) {
            throw new IllegalStateException("Seckill failed by unknown reason");
        }
        // 记录秒杀 Lua 预检的结果，用于统计成功率、库存不足与一人一单被拒绝次数
        metricsRecorder.recordSeckillPrecheckResult(result);
        if (result == 1L) {
            throw new IllegalStateException("Seckill stock is not enough");
        }
        if (result == 2L) {
            throw new IllegalStateException("User has already placed an order for this activity");
        }

        // 3. 通过 Redis 自增生成全局唯一订单 ID（高位时间戳 + 低位序列号）
        Long orderId = redisIdWorker.nextId("order");

        // 4. 将订单信息发送到 MQ，由消费者异步创建订单并扣减数据库库存
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("userId", userId);
        payload.put("activityId", activityId);

        try {
            rabbitTemplate.convertAndSend(MqConfig.SECKILL_EXCHANGE, "seckill", payload);
        } catch (Exception e) {
            log.error("Send seckill order message failed", e);
            // 发送 MQ 消息失败，可考虑补偿或告警；此时 Redis 中的库存已经扣减
        }

        return orderId;
    }
}


