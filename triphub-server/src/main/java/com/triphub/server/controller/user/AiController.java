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
import lombok.extern.slf4j.Slf4j;
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
 *
 * 目前主要提供：
 * - /user/ai/trip-plan：结合用户画像和目的地信息，调用外部 LLM 生成行程解释，并创建一条 Trip 记录。
 */
@RestController
@RequestMapping("/user/ai")
@Slf4j
@RequiredArgsConstructor
public class AiController {

    private final TripService tripService;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;
    private final AiClient aiClient;

    /**
     * AI 行程规划接口：生成行程解释文案并创建一条 Trip 记录。
     */
    @PostMapping("/trip-plan")
    public Result<AiTripPlanVO> generateTripPlan(@RequestBody TripAiPlanRequestDTO dto) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        if (dto == null || !StringUtils.hasText(dto.getDestinationCity())
                || dto.getDays() == null || dto.getDays() <= 0) {
            return Result.error("目的地或天数不能为空");
        }

        // 加载当前用户画像 JSON，如果存在则反序列化为 Map。
        UserProfile profile = userProfileService.getByUserId(userId);
        Map<String, Object> profileMap = parseProfile(profile);

        // 构造一个简单的 Trip 实体（仅包含基本骨架，后续可在前端继续按天编辑）。
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

        // 为了演示方便，默认创建为私有行程，后续如需公开可由客户端修改。
        trip.setVisibility(0);
        tripService.save(trip);

        // 调用外部大模型生成本次行程的解释文案。
        log.info("AI 行程规划开始: userId={}, destinationCity={}, days={}, topTag={}",
                userId, dto.getDestinationCity(), dto.getDays(), tagSummary);
        String explanation = callLlmForExplanation(profileMap, dto.getDestinationCity(), dto.getDays(), tagSummary);
        log.info("AI 行程规划结束: userId={}, tripId={}, hasExplanation={}",
                userId, trip.getId(), StringUtils.hasText(explanation));

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
            // 如果大模型不可用，则回退为本地拼接的一段简单中文解释。
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



