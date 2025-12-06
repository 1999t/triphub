package com.triphub.pojo.dto;

import lombok.Data;

import java.time.LocalTime;

/**
 * DTO for updating an existing trip item.
 */
@Data
public class TripItemUpdateDTO {

    /**
     * Item primary key ID
     */
    private Long id;

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


