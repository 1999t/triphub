package com.triphub.server.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.context.BaseContext;
import com.triphub.pojo.dto.TripAiPlanRequestDTO;
import com.triphub.pojo.entity.Trip;
import com.triphub.pojo.entity.UserProfile;
import com.triphub.pojo.vo.AiTripPlanVO;
import com.triphub.server.limit.SimpleRateLimiter;
import com.triphub.server.service.TripFavoriteService;
import com.triphub.server.service.TripService;
import com.triphub.server.service.UserProfileService;
import com.triphub.server.utils.AiClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * TripHub AI 行程规划编排器（精简版）。
 *
 * 按 KISS 原则实现一个最小化的：
 * - 状态机：WAIT_FOR_REQUEST -> AUTH_LIMIT_CHECK -> PLAN_LOOP -> SAVE_RESULT -> DONE/ERROR
 * - 行为树：PLAN_LOOP 阶段支持最多 2 轮生成 + QA + 本地兜底
 * - 策略：根据是否冷启动用户决定最大重试次数与降级策略
 *
 * 外部只需要调用 {@link #run(TripAiPlanRequestDTO, String)}，即可得到最终的 AiTripPlanVO。
 * 更详细的执行信息（状态、轮次等）通过 OrchestratorResponse 返回，可用于日志与监控。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TripPlanOrchestrator {

    private final TripService tripService;
    private final UserProfileService userProfileService;
    private final TripFavoriteService tripFavoriteService;
    private final ObjectMapper objectMapper;
    private final AiClient aiClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final SimpleRateLimiter simpleRateLimiter;

    /**
     * 对外主入口：执行一次 AI 行程规划的完整编排。
     */
    public OrchestratorResponse run(TripAiPlanRequestDTO dto, String idempotencyKey) {
        OrchestratorResponse resp = new OrchestratorResponse();
        resp.setRounds(new ArrayList<>());

        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            resp.setStatus("ERROR");
            resp.setErrorMessage("未登录或 token 无效");
            return resp;
        }
        if (dto == null || !StringUtils.hasText(dto.getDestinationCity())
                || dto.getDays() == null || dto.getDays() <= 0) {
            resp.setStatus("ERROR");
            resp.setErrorMessage("目的地或天数不能为空");
            return resp;
        }

        TripPlanState state = TripPlanState.WAIT_FOR_REQUEST;
        TripPlanContext ctx = new TripPlanContext();
        ctx.setUserId(userId);
        ctx.setRequest(dto);
        ctx.setIdempotencyKey(idempotencyKey);

        try {
            while (state != TripPlanState.DONE && state != TripPlanState.ERROR) {
                switch (state) {
                    case WAIT_FOR_REQUEST -> {
                        // 这里只做最小校验，主要由上层 Controller 做参数校验。
                        state = TripPlanState.AUTH_LIMIT_CHECK;
                    }
                    case AUTH_LIMIT_CHECK -> {
                        // 简单限流：保持与原实现一致。
                        boolean allowed = simpleRateLimiter.tryAcquire("ai:trip-plan:user",
                                String.valueOf(userId), 60, 10);
                        if (!allowed) {
                            resp.setStatus("ERROR");
                            resp.setCheckerState("RATE_LIMIT_BLOCKED");
                            resp.setErrorMessage("AI 请求过于频繁，请稍后再试");
                            state = TripPlanState.ERROR;
                            break;
                        }

                        // 幂等性检查：若命中缓存，直接返回结果，不再进入行为树。
                        AiTripPlanVO cached = tryLoadIdempotentResult(idempotencyKey);
                        if (cached != null) {
                            resp.setStatus("DONE");
                            resp.setCheckerState("IDEMPOTENT_HIT");
                            resp.setFinalResult(cached);
                            resp.setReport("命中幂等缓存，本次未调用外部 LLM");
                            state = TripPlanState.DONE;
                        } else {
                            resp.setCheckerState("READY");
                            state = TripPlanState.PLAN_LOOP;
                        }
                    }
                    case PLAN_LOOP -> {
                        TripPlanStrategy strategy = buildStrategy(ctx);
                        executePlanLoop(ctx, strategy, resp);
                        if (!StringUtils.hasText(ctx.getExplanation())) {
                            resp.setStatus("ERROR");
                            resp.setErrorMessage("AI 行程规划生成失败");
                            state = TripPlanState.ERROR;
                        } else {
                            state = TripPlanState.SAVE_RESULT;
                        }
                    }
                    case SAVE_RESULT -> {
                        AiTripPlanVO vo = saveResultAndBuildVo(ctx);
                        ctx.setResultVo(vo);
                        cacheIdempotentResult(ctx.getIdempotencyKey(), vo);
                        resp.setFinalResult(vo);
                        resp.setStatus("DONE");
                        if (!StringUtils.hasText(resp.getReport())) {
                            resp.setReport("行程创建成功并完成解释生成");
                        }
                        state = TripPlanState.DONE;
                    }
                    default -> {
                        resp.setStatus("ERROR");
                        resp.setErrorMessage("未知状态");
                        state = TripPlanState.ERROR;
                    }
                }
            }
        } catch (Exception e) {
            log.error("TripPlanOrchestrator 执行异常", e);
            resp.setStatus("ERROR");
            if (!StringUtils.hasText(resp.getErrorMessage())) {
                resp.setErrorMessage("服务器内部错误，请稍后重试");
            }
        }
        return resp;
    }

    /**
     * PLAN_LOOP 阶段：执行多轮「生成 + QA」，必要时降级为本地解释。
     */
    private void executePlanLoop(TripPlanContext ctx, TripPlanStrategy strategy, OrchestratorResponse resp) {
        TripAiPlanRequestDTO dto = ctx.getRequest();

        // 准备上下文：用户画像 / 收藏行程 / Trip 草稿
        prepareContext(ctx);

        String destinationCity = dto.getDestinationCity();
        Integer days = dto.getDays();
        String tagSummary = ctx.getTopTagName();
        String recentFavoritesJson = ctx.getRecentFavoritesJson();

        for (int round = 1; round <= strategy.getMaxRetries(); round++) {
            TripPlanRoundRecord roundRecord = new TripPlanRoundRecord();
            roundRecord.setRound(round);

            // Collector：调用外部 LLM 生成解释文案
            String explanationCandidate = callLlmForExplanation(
                    ctx.getProfileMap(),
                    destinationCity,
                    days,
                    tagSummary,
                    recentFavoritesJson,
                    round
            );
            String shortCollector = explanationCandidate;
            if (shortCollector != null && shortCollector.length() > 80) {
                shortCollector = shortCollector.substring(0, 80) + "...";
            }
            roundRecord.setCollectorResult(shortCollector);

            // 若 LLM 返回为空，根据策略决定是否直接降级。
            if (!StringUtils.hasText(explanationCandidate)) {
                roundRecord.setQaResult("LLM 返回为空");
                roundRecord.setPassed(false);
                resp.getRounds().add(roundRecord);

                if (round == strategy.getMaxRetries() && strategy.isAllowDegradedResult()) {
                    String fallback = buildLocalExplanation(ctx.getProfileMap(), destinationCity, days, tagSummary);
                    ctx.setExplanation(fallback);

                    TripPlanRoundRecord fallbackRecord = new TripPlanRoundRecord();
                    fallbackRecord.setRound(round);
                    String shortFallback = fallback;
                    if (shortFallback != null && shortFallback.length() > 80) {
                        shortFallback = shortFallback.substring(0, 80) + "...";
                    }
                    fallbackRecord.setCollectorResult(shortFallback);
                    fallbackRecord.setQaResult("使用本地兜底解释");
                    fallbackRecord.setPassed(true);
                    resp.getRounds().add(fallbackRecord);

                    resp.setReport("LLM 多轮返回为空，降级使用本地解释");
                    return;
                }
                continue;
            }

            // QA：做一些最基础的校验，避免明显异常结果。
            QaResult qa = simpleQa(explanationCandidate, destinationCity, days);
            roundRecord.setQaResult(qa.getMessage());
            roundRecord.setPassed(qa.isPassed());
            resp.getRounds().add(roundRecord);

            if (qa.isPassed()) {
                ctx.setExplanation(explanationCandidate);
                resp.setReport("LLM 生成解释通过 QA 校验");
                return;
            } else if (round == strategy.getMaxRetries() && strategy.isAllowDegradedResult()) {
                String fallback = buildLocalExplanation(ctx.getProfileMap(), destinationCity, days, tagSummary);
                ctx.setExplanation(fallback);

                TripPlanRoundRecord fallbackRecord = new TripPlanRoundRecord();
                fallbackRecord.setRound(round);
                String shortFallback = fallback;
                if (shortFallback != null && shortFallback.length() > 80) {
                    shortFallback = shortFallback.substring(0, 80) + "...";
                }
                fallbackRecord.setCollectorResult(shortFallback);
                fallbackRecord.setQaResult("QA 多轮不通过，降级使用本地解释");
                fallbackRecord.setPassed(true);
                resp.getRounds().add(fallbackRecord);

                resp.setReport("LLM 结果多轮 QA 不通过，降级使用本地解释");
                return;
            }
        }
    }

    /**
     * 准备行程规划上下文：用户画像、收藏行程摘要、Trip 草稿。
     */
    private void prepareContext(TripPlanContext ctx) {
        Long userId = ctx.getUserId();
        TripAiPlanRequestDTO dto = ctx.getRequest();

        // 用户画像
        UserProfile profile = userProfileService.getByUserId(userId);
        Map<String, Object> profileMap = parseProfile(profile);
        ctx.setProfileMap(profileMap);
        ctx.setColdUser(profileMap == null || profileMap.isEmpty());

        // topTagName
        String topTag = extractTopTagName(profileMap);
        ctx.setTopTagName(topTag);

        // 收藏行程 JSON
        String recentFavoritesJson = buildRecentFavoritesJson(userId);
        ctx.setRecentFavoritesJson(recentFavoritesJson);

        // Trip 草稿（尚未落库）
        Trip trip = new Trip();
        trip.setUserId(userId);
        trip.setDestinationCity(dto.getDestinationCity());
        trip.setDays(dto.getDays());

        LocalDate startDate = dto.getStartDate();
        if (startDate != null) {
            trip.setStartDate(startDate);
            trip.setEndDate(startDate.plusDays(dto.getDays() - 1L));
        }

        if (StringUtils.hasText(topTag)) {
            trip.setTitle(dto.getDestinationCity() + " " + dto.getDays() + "日" + topTag + "行（AI推荐）");
        } else {
            trip.setTitle(dto.getDestinationCity() + " " + dto.getDays() + "日行（AI推荐）");
        }
        // 默认私有行程
        trip.setVisibility(0);
        ctx.setTrip(trip);
    }

    /**
     * 根据上下文构建一个很简单的策略：
     * - 冷启动用户（画像为空）减少重试次数；
     * - 默认允许降级为本地解释。
     */
    private TripPlanStrategy buildStrategy(TripPlanContext ctx) {
        TripPlanStrategy strategy = new TripPlanStrategy();
        if (ctx.isColdUser()) {
            strategy.setMaxRetries(1);
        } else {
            strategy.setMaxRetries(2);
        }
        strategy.setAllowDegradedResult(true);
        return strategy;
    }

    /**
     * 保存 Trip 并构造最终 VO。
     */
    private AiTripPlanVO saveResultAndBuildVo(TripPlanContext ctx) throws DataAccessException {
        Trip trip = ctx.getTrip();
        // 先落库，再返回 Trip + 解释。
        tripService.save(trip);

        AiTripPlanVO vo = new AiTripPlanVO();
        vo.setTrip(trip);
        vo.setExplanation(ctx.getExplanation());
        return vo;
    }

    /**
     * 构造用于解释的 system + user prompt，调用外部 LLM。
     */
    private String callLlmForExplanation(Map<String, Object> profileMap,
                                         String city,
                                         Integer days,
                                         String topTagName,
                                         String recentFavoritesJson,
                                         int round) {
        String systemPrompt = "You are a travel planning assistant. "
                + "Given a user's travel preferences and a rough trip request, "
                + "you should generate a short, friendly explanation (2~3 sentences) "
                + "about why this trip plan matches the user's interests. "
                + "Always reply in Chinese.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Round: ").append(round).append("\n");
        userPrompt.append("User profile (JSON): ").append(safeToJson(profileMap)).append("\n");
        if (StringUtils.hasText(recentFavoritesJson)) {
            userPrompt.append("User recent favorite trips (JSON): ")
                    .append(recentFavoritesJson)
                    .append("\n");
        }
        userPrompt.append("Destination city: ").append(city).append("\n");
        userPrompt.append("Days: ").append(days).append("\n");
        if (StringUtils.hasText(topTagName)) {
            userPrompt.append("Top interest tag name: ").append(topTagName).append("\n");
        }
        userPrompt.append("Please answer in Chinese, ")
                .append("and only output the explanation text without any extra formatting.");

        String explanation = aiClient.chat(systemPrompt, userPrompt.toString());
        if (!StringUtils.hasText(explanation)) {
            return null;
        }
        return explanation.trim();
    }

    /**
     * 极简 QA：只做一些最基础的 sanity check。
     */
    private QaResult simpleQa(String explanation, String city, Integer days) {
        QaResult qa = new QaResult();
        if (!StringUtils.hasText(explanation)) {
            qa.setPassed(false);
            qa.setMessage("解释为空");
            return qa;
        }
        if (explanation.length() < 10) {
            qa.setPassed(false);
            qa.setMessage("解释过短，疑似异常");
            return qa;
        }
        if (StringUtils.hasText(city) && !explanation.contains(city)) {
            qa.setPassed(false);
            qa.setMessage("解释中未提及目的地城市，可能不相关");
            return qa;
        }
        qa.setPassed(true);
        qa.setMessage("基础 QA 通过");
        return qa;
    }

    /**
     * 本地兜底解释文案，避免完全失败。
     */
    private String buildLocalExplanation(Map<String, Object> profileMap,
                                         String city,
                                         Integer days,
                                         String topTagName) {
        StringBuilder sb = new StringBuilder();
        sb.append("本行程是根据你的基础出行偏好生成的");
        if (StringUtils.hasText(topTagName)) {
            sb.append("，特别考虑了你对「").append(topTagName).append("」的兴趣");
        }
        if (StringUtils.hasText(city)) {
            sb.append("，规划了一个为期 ").append(days).append(" 天的 ").append(city).append(" 行程");
        }
        Object budget = profileMap == null ? null : profileMap.get("budget");
        if (budget != null) {
            sb.append("，并参考了你偏好的预算区间：").append(budget);
        }
        sb.append("。");
        return sb.toString();
    }

    private Map<String, Object> parseProfile(UserProfile profile) {
        if (profile == null || !StringUtils.hasText(profile.getProfileJson())) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(profile.getProfileJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            // 若画像 JSON 已损坏，不应导致接口异常，直接回退为空画像。
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractTopTagName(Map<String, Object> profileMap) {
        if (profileMap == null) {
            return null;
        }
        Object tagsObj = profileMap.get("tags");
        if (!(tagsObj instanceof List)) {
            return null;
        }
        List<Object> tags = (List<Object>) tagsObj;
        String bestName = null;
        int bestWeight = -1;
        for (Object t : tags) {
            if (!(t instanceof Map)) {
                continue;
            }
            Map<String, Object> tag = (Map<String, Object>) t;
            Object weightObj = tag.get("weight");
            int weight = weightObj instanceof Number ? ((Number) weightObj).intValue() : 0;
            if (weight > bestWeight) {
                bestWeight = weight;
                Object nameObj = tag.get("name");
                bestName = nameObj == null ? null : String.valueOf(nameObj);
            }
        }
        return bestName;
    }

    private String buildRecentFavoritesJson(Long userId) {
        try {
            var favorites = tripFavoriteService.listRecentFavorites(userId, 5);
            if (favorites == null || favorites.isEmpty()) {
                return null;
            }
            var tripIds = favorites.stream()
                    .map(f -> f.getTripId())
                    .distinct()
                    .toList();
            var trips = tripService.listByIds(tripIds);
            if (trips == null || trips.isEmpty()) {
                return null;
            }
            var simpleList = trips.stream()
                    .filter(t -> t != null && t.getId() != null)
                    .map(t -> Map.of(
                            "tripId", t.getId(),
                            "title", t.getTitle(),
                            "destinationCity", t.getDestinationCity(),
                            "days", t.getDays()))
                    .toList();
            return objectMapper.writeValueAsString(simpleList);
        } catch (Exception e) {
            // 画像增强失败不影响主流程
            return null;
        }
    }

    private AiTripPlanVO tryLoadIdempotentResult(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return null;
        }
        try {
            String key = "ai:idemp:trip-plan:" + idempotencyKey;
            String json = stringRedisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, AiTripPlanVO.class);
        } catch (Exception e) {
            log.warn("读取 AI 幂等结果失败, key={}", idempotencyKey, e);
            return null;
        }
    }

    private void cacheIdempotentResult(String idempotencyKey, AiTripPlanVO vo) {
        if (!StringUtils.hasText(idempotencyKey) || vo == null) {
            return;
        }
        try {
            String key = "ai:idemp:trip-plan:" + idempotencyKey;
            String json = objectMapper.writeValueAsString(vo);
            stringRedisTemplate.opsForValue().set(key, json, 10, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("写入 AI 幂等结果失败, key={}", idempotencyKey, e);
        }
    }

    private String safeToJson(Map<String, Object> profileMap) {
        try {
            return objectMapper.writeValueAsString(profileMap == null ? Collections.emptyMap() : profileMap);
        } catch (Exception e) {
            return "{}";
        }
    }

    private enum TripPlanState {
        WAIT_FOR_REQUEST,
        AUTH_LIMIT_CHECK,
        PLAN_LOOP,
        SAVE_RESULT,
        DONE,
        ERROR
    }

    @Data
    public static class OrchestratorResponse {
        private String status; // DONE | ERROR
        private String checkerState;
        private List<TripPlanRoundRecord> rounds;
        private AiTripPlanVO finalResult;
        private String report;
        private String errorMessage;
    }

    @Data
    public static class TripPlanRoundRecord {
        private int round;
        private String collectorResult;
        private String qaResult;
        private boolean passed;
    }

    @Data
    private static class TripPlanContext {
        private Long userId;
        private TripAiPlanRequestDTO request;
        private String idempotencyKey;
        private Map<String, Object> profileMap;
        private boolean coldUser;
        private String topTagName;
        private String recentFavoritesJson;
        private Trip trip;
        private String explanation;
        private AiTripPlanVO resultVo;
    }

    @Data
    private static class TripPlanStrategy {
        private int maxRetries;
        private boolean allowDegradedResult;
    }

    @Data
    private static class QaResult {
        private boolean passed;
        private String message;
    }
}


