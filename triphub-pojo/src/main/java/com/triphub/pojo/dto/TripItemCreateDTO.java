package com.triphub.pojo.dto;

import lombok.Data;

import java.time.LocalTime;

/**
 * DTO for creating a new trip item.
 */
@Data
public class TripItemCreateDTO {

    /**
     * Trip ID
     */
    private Long tripId;

    /**
     * Index of day, starts from 1
     */
    private Integer dayIndex;

    /**
     * Item type: SCENIC/HOTEL/FOOD/TRAFFIC
     */
    private String type;

    /**
     * Related place ID (optional)
     */
    private Long placeId;

    private LocalTime startTime;

    private LocalTime endTime;

    private String memo;
}


