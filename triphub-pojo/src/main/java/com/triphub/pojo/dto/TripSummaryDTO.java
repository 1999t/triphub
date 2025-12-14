package com.triphub.pojo.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 行程摘要 DTO：用于热门榜单/推荐列表等场景，避免直接暴露 Trip 实体全部字段。
 *
 * 只保留展示所需字段，减少实体膨胀带来的接口兼容与敏感字段泄露风险。
 */
@Data
public class TripSummaryDTO {
    private Long id;
    private String title;
    private String destinationCity;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer days;
    private Integer viewCount;
    private Integer likeCount;
}


