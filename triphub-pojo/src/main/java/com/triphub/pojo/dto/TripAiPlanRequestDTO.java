package com.triphub.pojo.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * AI 行程规划请求体 DTO。
 * Request body for AI-based trip planning.
 */
@Data
public class TripAiPlanRequestDTO {

    /**
     * 目的地城市，例如「成都」。
     * Destination city.
     */
    private String destinationCity;

    /**
     * 出行天数。
     * Trip days.
     */
    private Integer days;

    /**
     * 开始日期，可选，用于推导结束日期。
     * Start date of the trip (optional).
     */
    private LocalDate startDate;
}



