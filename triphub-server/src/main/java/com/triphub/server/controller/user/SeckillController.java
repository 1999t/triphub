package com.triphub.server.controller.user;

import com.triphub.common.context.BaseContext;
import com.triphub.common.result.Result;
import com.triphub.server.limit.SimpleRateLimiter;
import com.triphub.server.service.SeckillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/user/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final SimpleRateLimiter rateLimiter;

    /**
     * 用户发起指定秒杀活动的下单请求。
     * 这里只做登录校验与异常包装，核心秒杀逻辑在 {@link SeckillService#seckill(Long)} 中完成。
     */
    @PostMapping("/{activityId}")
    public Result<Long> seckill(@PathVariable("activityId") Long activityId,
                                HttpServletRequest request) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        // 基于 IP + userId 进行秒杀接口限流，防止单用户或单 IP 高并发打爆系统
        String ip = request.getRemoteAddr();
        // 同一 IP 对同一活动 1 秒内最多 50 次请求（压测/恶意脚本场景）
        boolean ipAllowed = rateLimiter.tryAcquire("seckill:ip:" + activityId, ip, 1, 50);
        // 同一用户对同一活动 1 秒内最多 10 次请求
        boolean userAllowed = rateLimiter.tryAcquire("seckill:user:" + activityId, String.valueOf(userId), 1, 10);
        if (!ipAllowed || !userAllowed) {
            return Result.error("请求过于频繁，请稍后再试");
        }
        try {
            Long orderId = seckillService.seckill(activityId);
            return Result.success(orderId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}


