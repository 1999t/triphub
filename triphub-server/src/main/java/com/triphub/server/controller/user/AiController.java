package com.triphub.server.controller.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.context.BaseContext;
import com.triphub.common.result.Result;
import com.triphub.pojo.dto.TripAiPlanRequestDTO;
import com.triphub.pojo.entity.Trip;
import com.triphub.pojo.entity.UserProfile;
import com.triphub.pojo.vo.AiTripPlanVO;
import com.triphub.server.service.TripService;
import com.triphub.server.service.UserProfileService;
import com.triphub.server.utils.AiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AI 相关接口（RAG 味道 Demo，无向量库版本）。
 * AI-related APIs (RAG-like demo without real vector DB).
 *
 * 目前主要提供：
 * - /user/ai/trip-plan：结合用户画像和目的地信息，调用外部 LLM 生成行程解释，并创建一条 Trip 记录。
 */
@RestController
@RequestMapping("/user/ai")
@RequiredArgsConstructor
public class AiController {

    private final TripService tripService;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;
    private final AiClient aiClient;

    /**
     * AI 行程规划接口。
     * Generate AI-based trip plan explanation and create a trip record.
     */
    @PostMapping("/trip-plan")
    public Result<AiTripPlanVO> generateTripPlan(@RequestBody TripAiPlanRequestDTO dto) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        if (dto == null || !StringUtils.hasText(dto.getDestinationCity()) || dto.getDays() == null || dto.getDays() <= 0) {
            return Result.error("目的地或天数不能为空");
        }

        // Load user profile JSON and convert to map if exists.
        UserProfile profile = userProfileService.getByUserId(userId);
        Map<String, Object> profileMap = parseProfile(profile);

        // Build a simple trip entity (rough skeleton, detailed items can be edited later).
        Trip trip = new Trip();
        trip.setUserId(userId);
        trip.setDestinationCity(dto.getDestinationCity());
        trip.setDays(dto.getDays());

        LocalDate startDate = dto.getStartDate();
        if (startDate != null) {
            trip.setStartDate(startDate);
            trip.setEndDate(startDate.plusDays(dto.getDays() - 1L));
        }

        String tagSummary = extractTopTagName(profileMap);
        if (StringUtils.hasText(tagSummary)) {
            trip.setTitle(dto.getDestinationCity() + " " + dto.getDays() + "日" + tagSummary + "行（AI推荐）");
        } else {
            trip.setTitle(dto.getDestinationCity() + " " + dto.getDays() + "日行（AI推荐）");
        }

        // For demo: default to private trip; client can update later.
        trip.setVisibility(0);
        tripService.save(trip);

        // Call external LLM to generate explanation text.
        String explanation = callLlmForExplanation(profileMap, dto.getDestinationCity(), dto.getDays(), tagSummary);

        AiTripPlanVO vo = new AiTripPlanVO();
        vo.setTrip(trip);
        vo.setExplanation(explanation);
        return Result.success(vo);
    }

    private Map<String, Object> parseProfile(UserProfile profile) {
        if (profile == null || !StringUtils.hasText(profile.getProfileJson())) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(profile.getProfileJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            // Broken JSON should not crash the API, just fallback to empty profile.
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

    private String callLlmForExplanation(Map<String, Object> profileMap, String city, Integer days, String topTagName) {
        String systemPrompt = "You are a travel planning assistant. "
                + "Given a user's travel preferences and a rough trip request, "
                + "you should generate a short, friendly explanation (2~3 sentences) "
                + "about why this trip plan matches the user's interests.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("User profile (JSON): ").append(safeToJson(profileMap)).append("\n");
        userPrompt.append("Destination city: ").append(city).append("\n");
        userPrompt.append("Days: ").append(days).append("\n");
        if (StringUtils.hasText(topTagName)) {
            userPrompt.append("Top interest tag name: ").append(topTagName).append("\n");
        }
        userPrompt.append("Please answer in Chinese, and only output the explanation text without any extra formatting.");

        String explanation = aiClient.chat(systemPrompt, userPrompt.toString());
        if (!StringUtils.hasText(explanation)) {
            // fallback to a simple local explanation if LLM is not available
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
        return explanation;
    }

    private String safeToJson(Map<String, Object> profileMap) {
        try {
            return objectMapper.writeValueAsString(profileMap == null ? Collections.emptyMap() : profileMap);
        } catch (Exception e) {
            return "{}";
        }
    }
}



