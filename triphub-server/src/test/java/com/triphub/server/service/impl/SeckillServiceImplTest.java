package com.triphub.server.service.impl;

import com.triphub.common.context.BaseContext;
import com.triphub.pojo.entity.SeckillActivity;
import com.triphub.server.config.MqConfig;
import com.triphub.server.metrics.MetricsRecorder;
import com.triphub.server.service.SeckillActivityService;
import com.triphub.server.utils.RedisIdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SeckillServiceImpl 的基础单元测试：
 * - 覆盖未登录、活动不可用等异常分支；
 * - 覆盖 Lua 预检成功的正常下单流程。
 */
@ExtendWith(MockitoExtension.class)
class SeckillServiceImplTest {

    @Mock
    private SeckillActivityService seckillActivityService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedisIdWorker redisIdWorker;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private MetricsRecorder metricsRecorder;

    @InjectMocks
    private SeckillServiceImpl seckillService;

    @AfterEach
    void tearDown() {
        BaseContext.clear();
    }

    @Test
    void seckillShouldThrowWhenUserNotLoggedIn() {
        BaseContext.clear();
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> seckillService.seckill(1L)
        );
        assertTrue(ex.getMessage().contains("User not logged in"));
    }

    @Test
    void seckillShouldThrowWhenActivityNotAvailable() {
        BaseContext.setCurrentId(1L);
        when(seckillActivityService.getById(1L)).thenReturn(null);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> seckillService.seckill(1L)
        );
        assertTrue(ex.getMessage().contains("Seckill activity not available"));
    }

    @Test
    void seckillShouldSucceedWhenLuaPrecheckOk() {
        Long userId = 10L;
        Long activityId = 1L;
        BaseContext.setCurrentId(userId);

        SeckillActivity activity = new SeckillActivity();
        activity.setId(activityId);
        activity.setStatus(1);
        activity.setBeginTime(LocalDateTime.now().minusMinutes(1));
        activity.setEndTime(LocalDateTime.now().plusMinutes(5));
        when(seckillActivityService.getById(activityId)).thenReturn(activity);

        // Lua 预检返回 0 表示成功
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString()
        )).thenReturn(0L);

        when(redisIdWorker.nextId("order")).thenReturn(123L);

        Long orderId = seckillService.seckill(activityId);

        assertEquals(123L, orderId);
        verify(metricsRecorder).recordSeckillPrecheckResult(0L);
        verify(rabbitTemplate).convertAndSend(
                eq(MqConfig.SECKILL_EXCHANGE),
                eq("seckill"),
                argThat((Map<String, Object> payload) ->
                        payload.get("orderId").equals(123L)
                                && payload.get("userId").equals(userId)
                                && payload.get("activityId").equals(activityId)
                )
        );
    }
}


