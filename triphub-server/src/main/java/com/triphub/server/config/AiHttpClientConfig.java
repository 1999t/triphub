package com.triphub.server.config;

import com.triphub.common.properties.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * AI HTTP 客户端配置：
 * - 使用 JDK 17 自带 HttpClient（连接复用 + 低依赖）
 * - 超时由 AiProperties 控制
 */
@Configuration
@RequiredArgsConstructor
public class AiHttpClientConfig {

    private final AiProperties aiProperties;

    @Bean
    public HttpClient aiHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(aiProperties.getConnectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}


