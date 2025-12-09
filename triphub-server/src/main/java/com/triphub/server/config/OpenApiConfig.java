package com.triphub.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * SpringDoc OpenAPI 文档配置。
 *
 * 说明：
 * - 使用 springdoc-openapi-ui 提供的 /v3/api-docs 与 /swagger-ui.html 页面；
 * - 本配置主要补充基础的项目信息，便于在 Swagger UI 中展示。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI triphubOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("TripHub 后端接口文档")
                        .description("TripHub 行程管理 + AI 行程规划与推荐 Demo 项目的后端 API 文档")
                        .version("v1"))
                .servers(Collections.singletonList(
                        new Server().url("/").description("默认服务端")
                ));
    }
}


