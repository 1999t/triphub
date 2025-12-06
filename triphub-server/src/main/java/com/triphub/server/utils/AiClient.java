package com.triphub.server.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.properties.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 AI 客户端，用于调用 OpenAI 兼容的 Chat Completion 接口。
 * Extremely simple AI client for calling an OpenAI-compatible chat completion API.
 *
 * 设计目标：保持最小依赖，仅提供单一 chat 方法，使用 JSON in/out，不额外引入 SDK。
 * Keep it minimal: single chat method, JSON in/out, no SDK dependency.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiClient {

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    /**
     * 调用外部 AI 服务，传入 system + user prompt，返回纯文本回复内容。
     * Call AI provider with given system + user prompt, return plain text content.
     * 若配置不完整或调用失败，返回 null，由上层自行降级处理。
     * If call fails or configuration is missing, returns null.
     */
    public String chat(String systemPrompt, String userPrompt) {
        if (!StringUtils.hasText(aiProperties.getBaseUrl())
                || !StringUtils.hasText(aiProperties.getApiKey())
                || !StringUtils.hasText(aiProperties.getModel())) {
            log.warn("AI 配置不完整，跳过外部 LLM 调用");
            return null;
        }

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", aiProperties.getModel());

            Map<String, String> sysMsg = new HashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);

            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);

            body.put("messages", List.of(sysMsg, userMsg));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(aiProperties.getApiKey());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.exchange(
                    aiProperties.getBaseUrl(),
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("调用外部 LLM 失败，status={}, body={}", response.getStatusCode(), response.getBody());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode message = choices.get(0).get("message");
            if (message == null) {
                return null;
            }
            JsonNode content = message.get("content");
            return content == null ? null : content.asText();
        } catch (Exception e) {
            log.error("调用外部 LLM 失败", e);
            return null;
        }
    }
}



