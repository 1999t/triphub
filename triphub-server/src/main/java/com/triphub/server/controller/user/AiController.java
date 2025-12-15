package com.triphub.server.controller.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.context.BaseContext;
import com.triphub.common.result.Result;
import com.triphub.server.ai.TripPlanOrchestrator;
import com.triphub.pojo.dto.TripAiPlanRequestDTO;
import com.triphub.pojo.dto.AiAssistantRequestDTO;
import com.triphub.pojo.entity.UserProfile;
import com.triphub.pojo.vo.AiTripPlanVO;
import com.triphub.server.limit.SimpleRateLimiter;
import com.triphub.server.service.TripService;
import com.triphub.server.service.UserProfileService;
import com.triphub.server.service.TripFavoriteService;
import com.triphub.server.utils.AiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;
import java.util.List;

/**
 * AI 相关接口（RAG 味道 Demo，无向量库版本）。
 *
 * 目前主要提供：
 * - /user/ai/trip-plan：结合用户画像和目的地信息，调用外部 LLM 生成行程解释，并创建一条 Trip 记录。
 * - /user/ai/assistant：简单的「工具编排」示例，根据 type 聚合多种数据后交给大模型处理。
 */
@RestController
@RequestMapping("/user/ai")
@Slf4j
@RequiredArgsConstructor
public class AiController {

    private static final int MAX_PROFILE_JSON_CHARS = 2000;
    private static final int MAX_FAVORITES_JSON_CHARS = 1200;
    private static final int MAX_TITLE_CHARS = 40;

    private final TripService tripService;
    private final UserProfileService userProfileService;
    private final TripFavoriteService tripFavoriteService;
    private final ObjectMapper objectMapper;
    private final AiClient aiClient;
    private final TripPlanOrchestrator tripPlanOrchestrator;
    private final SimpleRateLimiter simpleRateLimiter;

    /**
     * AI 行程规划接口：生成行程解释文案并创建一条 Trip 记录。
     */
    @PostMapping("/trip-plan")
    public Result<AiTripPlanVO> generateTripPlan(@RequestBody TripAiPlanRequestDTO dto,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }

        log.info("AI 行程规划请求: userId={}, destinationCity={}, days={}, idempotencyKey={}",
                userId,
                dto != null ? dto.getDestinationCity() : null,
                dto != null ? dto.getDays() : null,
                idempotencyKey);

        TripPlanOrchestrator.OrchestratorResponse resp = tripPlanOrchestrator.run(dto, idempotencyKey);
        if (!"DONE".equals(resp.getStatus()) || resp.getFinalResult() == null) {
            String msg = resp.getErrorMessage();
            if (!StringUtils.hasText(msg)) {
                msg = "AI 行程规划失败，请稍后重试";
            }
            log.warn("AI 行程规划失败: userId={}, status={}, checkerState={}, report={}, error={}",
                    userId, resp.getStatus(), resp.getCheckerState(), resp.getReport(), resp.getErrorMessage());
            return Result.error(msg);
        }
        AiTripPlanVO vo = resp.getFinalResult();
        Long tripId = vo != null && vo.getTrip() != null ? vo.getTrip().getId() : null;
        log.info("AI 行程规划成功: userId={}, tripId={}, checkerState={}, report={}",
                userId, tripId, resp.getCheckerState(), resp.getReport());
        return Result.success(vo);
    }

    /**
     * 轻量级 AI 助手接口：根据 type 做不同的工具编排，然后交给 LLM 生成文本结果。
     *
     * type 示例：
     * - plan_trip：偏向行程规划场景，重点使用画像 + 最近收藏；
     * - recommend_trip：偏向推荐场景，可结合热门行程榜单等（当前实现主要做画像 + 收藏的编排）。
     */
    @PostMapping("/assistant")
    public Result<String> assistant(@RequestBody AiAssistantRequestDTO dto) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        if (dto == null || !StringUtils.hasText(dto.getType())) {
            return Result.error("type 不能为空");
        }

        boolean allowed = simpleRateLimiter.tryAcquire("ai:assistant:user", String.valueOf(userId), 60, 20);
        if (!allowed) {
            return Result.error("AI 请求过于频繁，请稍后再试");
        }

        String type = dto.getType();
        // 画像与收藏是所有类型共享的基础上下文
        UserProfile profile = userProfileService.getByUserId(userId);
        Map<String, Object> profileMap = parseProfile(profile);
        String topTagName = extractTopTagName(profileMap);
        String recentFavoritesJson = buildRecentFavoritesJson(userId);

        String systemPrompt = "You are a smart travel assistant. "
                + "You will receive user profile, recent favorites and an intent type, "
                + "and should reply in Chinese with a concise answer.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("User profile (JSON): ")
                .append(safeToJsonWithLimit(profileMap, MAX_PROFILE_JSON_CHARS))
                .append("\n");
        if (StringUtils.hasText(recentFavoritesJson)) {
            userPrompt.append("User recent favorite trips (JSON): ")
                    .append(trimToMaxChars(recentFavoritesJson, MAX_FAVORITES_JSON_CHARS))
                    .append("\n");
        }
        userPrompt.append("Assistant type: ").append(type).append("\n");
        if (StringUtils.hasText(dto.getDestinationCity())) {
            userPrompt.append("Destination city: ").append(dto.getDestinationCity()).append("\n");
        }
        if (dto.getDays() != null && dto.getDays() > 0) {
            userPrompt.append("Days: ").append(dto.getDays()).append("\n");
        }
        if (StringUtils.hasText(topTagName)) {
            userPrompt.append("Top interest tag name: ").append(topTagName).append("\n");
        }
        if (StringUtils.hasText(dto.getQuestion())) {
            userPrompt.append("User question: ").append(dto.getQuestion()).append("\n");
        }
        userPrompt.append("Constraints: ")
                .append("1) Reply in Chinese. ")
                .append("2) No markdown/code/json, only plain text. ")
                .append("3) Keep it concise and actionable.");

        String answer = aiClient.chat(systemPrompt, userPrompt.toString());
        if (!StringUtils.hasText(answer)) {
            String fallback = buildLocalAssistantFallback(type,
                    dto.getDestinationCity(),
                    dto.getDays(),
                    topTagName,
                    StringUtils.hasText(recentFavoritesJson));
            return Result.success(fallback);
        }
        return Result.success(answer);
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

    /**
     * 构造当前用户最近收藏行程的精简 JSON，用于作为 LLM 的参考上下文。
     */
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
                            "title", trimToMaxChars(t.getTitle(), MAX_TITLE_CHARS),
                            "destinationCity", t.getDestinationCity(),
                            "days", t.getDays()))
                    .toList();
            String json = objectMapper.writeValueAsString(simpleList);
            return trimToMaxChars(json, MAX_FAVORITES_JSON_CHARS);
        } catch (Exception e) {
            // 画像增强失败不影响主流程
            return null;
        }
    }

    private String safeToJson(Map<String, Object> profileMap) {
        try {
            return objectMapper.writeValueAsString(profileMap == null ? Collections.emptyMap() : profileMap);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String safeToJsonWithLimit(Map<String, Object> profileMap, int maxChars) {
        return trimToMaxChars(safeToJson(profileMap), maxChars);
    }

    private String trimToMaxChars(String s, int maxChars) {
        if (!StringUtils.hasText(s)) {
            return s;
        }
        if (maxChars <= 0 || s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "...";
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

    private String buildLocalAssistantFallback(String type,
                                              String city,
                                              Integer days,
                                              String topTagName,
                                              boolean hasRecentFavorites) {
        StringBuilder sb = new StringBuilder();
        sb.append("AI 当前不可用，我先基于你已有的偏好给一个可执行的建议：");
        if (StringUtils.hasText(type)) {
            sb.append("（").append(type).append("）");
        }
        if (StringUtils.hasText(topTagName)) {
            sb.append("，优先考虑「").append(topTagName).append("」相关安排");
        }
        if (StringUtils.hasText(city) && days != null && days > 0) {
            sb.append("，按 ").append(days).append(" 天 ").append(city).append(" 的节奏做行程拆分");
        }
        if (hasRecentFavorites) {
            sb.append("，并参考你最近收藏的行程风格做取舍");
        }
        sb.append("。你可以把预算/同行人/偏好节奏补充一句，我再帮你细化。");
        return sb.toString();
    }
}



