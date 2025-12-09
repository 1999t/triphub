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

    private final TripService tripService;
    private final UserProfileService userProfileService;
    private final TripFavoriteService tripFavoriteService;
    private final ObjectMapper objectMapper;
    private final AiClient aiClient;
    private final TripPlanOrchestrator tripPlanOrchestrator;

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

        String type = dto.getType();
        // 画像与收藏是所有类型共享的基础上下文
        UserProfile profile = userProfileService.getByUserId(userId);
        Map<String, Object> profileMap = parseProfile(profile);
        String recentFavoritesJson = buildRecentFavoritesJson(userId);

        String systemPrompt = "You are a smart travel assistant. "
                + "You will receive user profile, recent favorites and an intent type, "
                + "and should reply in Chinese with a concise answer.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("User profile (JSON): ").append(safeToJson(profileMap)).append("\n");
        if (StringUtils.hasText(recentFavoritesJson)) {
            userPrompt.append("User recent favorite trips (JSON): ")
                    .append(recentFavoritesJson)
                    .append("\n");
        }
        userPrompt.append("Assistant type: ").append(type).append("\n");
        if (StringUtils.hasText(dto.getDestinationCity())) {
            userPrompt.append("Destination city: ").append(dto.getDestinationCity()).append("\n");
        }
        if (dto.getDays() != null && dto.getDays() > 0) {
            userPrompt.append("Days: ").append(dto.getDays()).append("\n");
        }
        if (StringUtils.hasText(dto.getQuestion())) {
            userPrompt.append("User question: ").append(dto.getQuestion()).append("\n");
        }
        userPrompt.append("Please answer in Chinese, focusing on the given type and context. ")
                .append("Do not output JSON, only natural language.");

        String answer = aiClient.chat(systemPrompt, userPrompt.toString());
        if (!StringUtils.hasText(answer)) {
            return Result.error("AI 暂时不可用，请稍后重试");
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

    private String safeToJson(Map<String, Object> profileMap) {
        try {
            return objectMapper.writeValueAsString(profileMap == null ? Collections.emptyMap() : profileMap);
        } catch (Exception e) {
            return "{}";
        }
    }
}



