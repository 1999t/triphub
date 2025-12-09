package com.triphub.server.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 统一的业务指标记录器。
 *
 * 说明：
 * - 当前使用 Micrometer 的 MeterRegistry 记录基础 Counter 指标；
 * - 若未启用 Prometheus 等外部监控系统，也不会影响业务逻辑，只是在本地内存里维护统计值；
 * - 指标命名与 Tag 设计参考「组件.业务.动作」，便于后续在监控面板上按模块聚合展示。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MetricsRecorder {

    private final MeterRegistry meterRegistry;

    /**
     * 记录行程详情缓存的命中/未命中情况。
     *
     * @param hit true 表示命中缓存，false 表示回源数据库
     */
    public void recordTripCacheHit(boolean hit) {
        try {
            String outcome = hit ? "hit" : "miss";
            meterRegistry.counter("triphub.trip.cache", "outcome", outcome).increment();
        } catch (Exception e) {
            log.debug("记录缓存命中指标失败: {}", e.getMessage());
        }
    }

    /**
     * 记录热门行程/热门目的地榜单分数更新次数。
     */
    public void recordHotRankingUpdate(String type) {
        try {
            meterRegistry.counter("triphub.hot_ranking.update", "type", type).increment();
        } catch (Exception e) {
            log.debug("记录热门榜单更新指标失败: {}", e.getMessage());
        }
    }

}


