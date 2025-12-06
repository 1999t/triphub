package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.triphub.common.constant.RedisConstants;
import com.triphub.pojo.entity.SeckillActivity;
import com.triphub.server.mapper.SeckillActivityMapper;
import com.triphub.server.service.SeckillActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeckillActivityServiceImpl extends ServiceImpl<SeckillActivityMapper, SeckillActivity>
        implements SeckillActivityService {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 应用启动后，将 MySQL 中已开启的秒杀活动库存预热到 Redis。
     * 只在 Redis 中不存在对应 key 时才写入，避免覆盖运行时手工调整的库存。
     */
    @PostConstruct
    public void loadSeckillStock() {
        List<SeckillActivity> activities = this.lambdaQuery()
                .eq(SeckillActivity::getStatus, 1)
                .gt(SeckillActivity::getStock, 0)
                .list();
        if (activities == null || activities.isEmpty()) {
            return;
        }
        for (SeckillActivity activity : activities) {
            Long id = activity.getId();
            if (id == null) {
                continue;
            }
            String key = RedisConstants.SECKILL_STOCK_KEY + id;
            String stockStr = String.valueOf(activity.getStock());
            Boolean set = stringRedisTemplate.opsForValue().setIfAbsent(key, stockStr);
            if (Boolean.TRUE.equals(set)) {
                log.info("init seckill stock for activityId={}, stock={}", id, activity.getStock());
            } else {
                log.info("skip init seckill stock for activityId={} because Redis key already exists", id);
            }
        }
    }
}


