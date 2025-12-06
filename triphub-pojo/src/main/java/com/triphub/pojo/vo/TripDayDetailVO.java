package com.triphub.pojo.vo;

import com.triphub.pojo.entity.TripItem;
import lombok.Data;

import java.util.List;

/**
 * View object for a specific trip day: note + item list.
 */
@Data
public class TripDayDetailVO {

    /**
     * Primary key of trip day, may be null if not created yet.
     */
    private Long id;

    /**
     * Trip ID
     */
    private Long tripId;

    /**
     * Index of day, starts from 1.
     */
    private Integer dayIndex;

    /**
     * Note for this day.
     */
    private String note;

    /**
     * Items of this day.
     */
    private List<TripItem> items;
}


