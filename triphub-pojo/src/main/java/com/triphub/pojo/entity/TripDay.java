package com.triphub.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("trip_day")
public class TripDay {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tripId;

    private Integer dayIndex;

    private String note;
}


