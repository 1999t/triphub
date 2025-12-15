package com.triphub.server.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

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

    /**
     * 记录 AI Chat 调用结果（成功/失败/被熔断/被隔离等）。
     */
    public void recordAiChatCall(String outcome, String reason, String model) {
        try {
            meterRegistry.counter("triphub.ai.chat.call",
                    "outcome", safe(outcome),
                    "reason", safe(reason),
                    "model", safe(model)).increment();
        } catch (Exception e) {
            log.debug("记录 AI 调用指标失败: {}", e.getMessage());
        }
    }

    /**
     * 记录 AI Chat 调用耗时。
     */
    public void recordAiChatLatencyMs(long latencyMs, String outcome, String model) {
        try {
            meterRegistry.timer("triphub.ai.chat.latency",
                    "outcome", safe(outcome),
                    "model", safe(model))
                    .record(latencyMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("记录 AI 耗时指标失败: {}", e.getMessage());
        }
    }

    private String safe(String s) {
        if (s == null || s.isBlank()) {
            return "unknown";
        }
        // tag 不宜过长，避免高基数/卡面板
        return s.length() > 32 ? s.substring(0, 32) : s;
    }
}


