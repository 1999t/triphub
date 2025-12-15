package com.triphub.server.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.properties.AiProperties;
import com.triphub.server.metrics.MetricsRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 极简 AI 客户端，用于调用 OpenAI 兼容的 Chat Completion 接口。
 * Extremely simple AI client for calling an OpenAI-compatible chat completion API.
 *
 * 设计目标：保持最小依赖，仅提供单一 chat 方法，使用 JSON in/out，不额外引入 SDK。
 * Keep it minimal: single chat method, JSON in/out, no SDK dependency.
 */
@Component
@Slf4j
public class AiClient {

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient aiHttpClient;
    private final MetricsRecorder metricsRecorder;

    public AiClient(AiProperties aiProperties,
                    ObjectMapper objectMapper,
                    HttpClient aiHttpClient,
                    MetricsRecorder metricsRecorder) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.aiHttpClient = aiHttpClient;
        this.metricsRecorder = metricsRecorder;
    }

    /**
     * 调用外部 AI 服务，传入 system + user prompt，返回纯文本回复内容。
     * Call AI provider with given system + user prompt, return plain text content.
     * 若配置不完整或调用失败，返回 null，由上层自行降级处理。
     * If call fails or configuration is missing, returns null.
     */
    public String chat(String systemPrompt, String userPrompt) {
        String model = aiProperties.getModel();
        if (!StringUtils.hasText(aiProperties.getBaseUrl())
                || !StringUtils.hasText(aiProperties.getApiKey())
                || !StringUtils.hasText(model)) {
            log.warn("AI 配置不完整，跳过外部 LLM 调用");
            metricsRecorder.recordAiChatCall("skipped", "config_missing", model);
            return null;
        }

        long startNs = System.nanoTime();
        int promptBytes = safeBytes(systemPrompt) + safeBytes(userPrompt);
        int attempts = 0;
        try {
            int maxRetries = Math.max(0, aiProperties.getMaxRetries());
            int maxAttempts = 1 + maxRetries;
            for (int i = 1; i <= maxAttempts; i++) {
                attempts = i;
                AiHttpResult r = doHttpCall(systemPrompt, userPrompt);
                if (r.success) {
                    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
                    metricsRecorder.recordAiChatLatencyMs(latencyMs, "success", model);
                    metricsRecorder.recordAiChatCall("success", "ok", model);
                    log.info("AI chat success: model={}, latencyMs={}, attempts={}, promptBytes={}, respBytes={}",
                            model, latencyMs, attempts, promptBytes, r.responseBytes);
                    return r.content;
                }

                boolean retriable = isRetriable(r.statusCode, r.errorType);
                if (!retriable || i == maxAttempts) {
                    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
                    metricsRecorder.recordAiChatLatencyMs(latencyMs, "fail", model);
                    metricsRecorder.recordAiChatCall("fail", r.errorType, model);
                    log.warn("AI chat fail: model={}, latencyMs={}, attempts={}, statusCode={}, errorType={}, promptBytes={}, respBytes={}",
                            model, latencyMs, attempts, r.statusCode, r.errorType, promptBytes, r.responseBytes);
                    return null;
                }
                // KISS：不做退避与 jitter，避免行为难以预测；需要时再加。
            }
            return null;
        } catch (Exception e) {
            metricsRecorder.recordAiChatCall("fail", "exception", model);
            log.error("调用外部 LLM 失败", e);
            return null;
        }
    }

    private AiHttpResult doHttpCall(String systemPrompt, String userPrompt) {
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
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiProperties.getBaseUrl()))
                    .timeout(Duration.ofMillis(Math.max(1, aiProperties.getRequestTimeoutMs())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiProperties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = aiHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            String respBody = response.body();
            int respBytes = safeBytes(respBody);

            if (code / 100 != 2 || !StringUtils.hasText(respBody)) {
                return AiHttpResult.fail(code, "http_" + code, respBytes);
            }

            JsonNode root = objectMapper.readTree(respBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return AiHttpResult.fail(code, "bad_response_no_choices", respBytes);
            }
            JsonNode message = choices.get(0).get("message");
            if (message == null) {
                return AiHttpResult.fail(code, "bad_response_no_message", respBytes);
            }
            JsonNode content = message.get("content");
            if (content == null || !StringUtils.hasText(content.asText())) {
                return AiHttpResult.fail(code, "bad_response_empty_content", respBytes);
            }
            return AiHttpResult.ok(content.asText().trim(), respBytes);
        } catch (java.net.http.HttpTimeoutException te) {
            return AiHttpResult.fail(0, "timeout", 0);
        } catch (Exception e) {
            return AiHttpResult.fail(0, "exception", 0);
        }
    }

    private boolean isRetriable(int statusCode, String errorType) {
        if ("timeout".equals(errorType)) {
            return true;
        }
        if (statusCode == 429) {
            return true;
        }
        return statusCode / 100 == 5;
    }

    private int safeBytes(String s) {
        if (!StringUtils.hasText(s)) {
            return 0;
        }
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static class AiHttpResult {
        private final boolean success;
        private final String content;
        private final int statusCode;
        private final String errorType;
        private final int responseBytes;

        private AiHttpResult(boolean success, String content, int statusCode, String errorType, int responseBytes) {
            this.success = success;
            this.content = content;
            this.statusCode = statusCode;
            this.errorType = errorType;
            this.responseBytes = responseBytes;
        }

        static AiHttpResult ok(String content, int responseBytes) {
            return new AiHttpResult(true, content, 200, "ok", responseBytes);
        }

        static AiHttpResult fail(int statusCode, String errorType, int responseBytes) {
            return new AiHttpResult(false, null, statusCode, errorType, responseBytes);
        }
    }
}



