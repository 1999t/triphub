package com.triphub.pojo.vo;

import lombok.Data;

/**
 * Simple recommended trip view with human readable reason.
 */
@Data
public class RecommendedTripVO {

    private Long tripId;

    private String title;

    private String destinationCity;

    private Integer viewCount;

    /**
     * Text reason for recommendation, generated based on user profile and popularity.
     */
    private String reason;
}



