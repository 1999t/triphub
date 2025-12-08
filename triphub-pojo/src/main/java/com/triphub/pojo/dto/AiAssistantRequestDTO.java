package com.triphub.pojo.dto;

import lombok.Data;

/**
 * 轻量级 AI 助手请求参数。
 */
@Data
public class AiAssistantRequestDTO {

    /**
     * 助手类型，例如：plan_trip / recommend_trip / qa 等。
     */
    private String type;

    /**
     * 目的地城市（可选）。
     */
    private String destinationCity;

    /**
     * 行程天数（可选）。
     */
    private Integer days;

    /**
     * 用户自然语言问题（可选）。
     */
    private String question;
}



