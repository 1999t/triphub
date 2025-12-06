package com.triphub.pojo.dto;

import lombok.Data;

/**
 * DTO for editing note of a specific trip day.
 */
@Data
public class TripDayNoteDTO {

    /**
     * Trip ID
     */
    private Long tripId;

    /**
     * Index of day, starts from 1
     */
    private Integer dayIndex;

    /**
     * Note of this day
     */
    private String note;
}


