package com.triphub.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalTime;

@Data
@TableName("trip_item")
public class TripItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tripDayId;

    /**
     * SCENIC/HOTEL/FOOD/TRAFFIC
     */
    private String type;

    private Long placeId;

    private LocalTime startTime;

    private LocalTime endTime;

    private String memo;
}


