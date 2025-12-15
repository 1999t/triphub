package com.triphub.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 配置属性（如 OpenAI 兼容接口的地址、密钥、模型等）。
 * AI provider configuration (e.g. OpenAI compatible API).
 */
@Data
@ConfigurationProperties(prefix = "triphub.ai")
public class AiProperties {

    /**
     * Chat Completion 接口的基础 URL，例如：https://api.openai.com/v1/chat/completions
     * Base URL of chat completion endpoint, e.g. https://api.openai.com/v1/chat/completions
     */
    private String baseUrl;

    /**
     * AI 服务提供方的 API Key。
     * API key for the AI provider.
     */
    private String apiKey;

    /**
     * 模型名称，例如：gpt-4.1-mini。
     * Model name, e.g. gpt-4.1-mini.
     */
    private String model;

    /**
     * 连接超时（毫秒）。
     */
    private int connectTimeoutMs = 1000;

    /**
     * 单次请求超时（毫秒），包含网络 IO 与对端响应等待。
     */
    private int requestTimeoutMs = 5000;

    /**
     * 最大重试次数（不含首次请求）。
     * 仅对 429/5xx/超时 这类可重试错误生效。
     */
    private int maxRetries = 1;
}



