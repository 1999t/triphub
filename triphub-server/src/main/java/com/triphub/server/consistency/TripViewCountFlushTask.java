package com.triphub.server.consistency;

import com.triphub.common.constant.RedisConstants;
import com.triphub.server.mapper.TripMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 行程浏览量 view_count 增量落库任务：
 * - 请求路径只做 Redis 增量（Hash），避免每次详情都写 DB；
 * - 定时批量把增量刷回 DB，实现最终一致。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TripViewCountFlushTask {

    private final StringRedisTemplate stringRedisTemplate;
    private final TripMapper tripMapper;

    /**
     * 原子读取并删除 Hash 的 Lua：
     * 返回 HGETALL 结果（field1, value1, field2, value2...），然后 DEL key。
     */
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> HGETALL_DEL_SCRIPT = new DefaultRedisScript<>(
            "local res = redis.call('HGETALL', KEYS[1]); " +
                    "if res and #res > 0 then redis.call('DEL', KEYS[1]); end; " +
                    "return res;",
            List.class
    );

    /**
     * 每 10 秒刷一次，Demo 取一个比较激进的频率，面试也好讲（最终一致）。
     */
    @Scheduled(fixedDelay = 10_000L)
    public void flushViewCountDeltas() {
        List<Object> raw = executeFetchAndClear();
        if (raw == null || raw.isEmpty()) {
            return;
        }

        Map<Long, Long> deltaMap = parse(raw);
        if (deltaMap.isEmpty()) {
            return;
        }

        int updated = 0;
        for (Map.Entry<Long, Long> e : deltaMap.entrySet()) {
            Long tripId = e.getKey();
            Long delta = e.getValue();
            if (tripId == null || delta == null || delta <= 0) {
                continue;
            }
            updated += tripMapper.updateViewCountDelta(tripId, delta);
        }
        log.info("flush view_count deltas done, tripCount={}, updatedRows={}", deltaMap.size(), updated);
    }

    private List<Object> executeFetchAndClear() {
        try {
            Object res = stringRedisTemplate.execute(
                    HGETALL_DEL_SCRIPT,
                    Collections.singletonList(RedisConstants.TRIP_VIEW_COUNT_DELTA_HASH)
            );
            if (res instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) res;
                return list;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("flush view_count deltas failed when read redis", e);
            return Collections.emptyList();
        }
    }

    private Map<Long, Long> parse(List<Object> raw) {
        Map<Long, Long> map = new HashMap<>();
        for (int i = 0; i + 1 < raw.size(); i += 2) {
            String field = String.valueOf(raw.get(i));
            String value = String.valueOf(raw.get(i + 1));
            try {
                Long tripId = Long.valueOf(field);
                Long delta = Long.valueOf(value);
                if (delta != null && delta > 0) {
                    map.merge(tripId, delta, Long::sum);
                }
            } catch (Exception ignore) {
                // ignore bad field/value
            }
        }
        return map;
    }
}


