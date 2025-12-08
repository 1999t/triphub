package com.triphub.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行程收藏实体，对应 trip_favorite 表。
 */
@Data
@TableName("trip_favorite")
public class TripFavorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long tripId;

    private LocalDateTime createTime;
}



