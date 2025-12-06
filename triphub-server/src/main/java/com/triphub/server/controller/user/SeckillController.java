package com.triphub.server.controller.user;

import com.triphub.common.context.BaseContext;
import com.triphub.common.result.Result;
import com.triphub.server.service.SeckillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    /**
     * 用户发起指定秒杀活动的下单请求。
     * 这里只做登录校验与异常包装，核心秒杀逻辑在 {@link SeckillService#seckill(Long)} 中完成。
     */
    @PostMapping("/{activityId}")
    public Result<Long> seckill(@PathVariable("activityId") Long activityId) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        try {
            Long orderId = seckillService.seckill(activityId);
            return Result.success(orderId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}


