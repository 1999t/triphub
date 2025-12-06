package com.triphub.pojo.vo;

import lombok.Data;

/**
 * 个性化推荐行程视图 VO。
 * Simple recommended trip view with human readable reason.
 *
 * 由热门行程榜单 + 用户画像计算出的推荐结果，每条记录附带一个人类可读的推荐理由。
 */
@Data
public class RecommendedTripVO {

    private Long tripId;

    private String title;

    private String destinationCity;

    private Integer viewCount;

    /**
     * 推荐理由文案，基于用户画像与热门程度生成。
     * Text reason for recommendation, generated based on user profile and popularity.
     */
    private String reason;
}



