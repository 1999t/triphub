package com.triphub.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_activity")
public class SeckillActivity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Title of seckill activity.
     */
    private String title;

    /**
     * Related place ID.
     */
    private Long placeId;

    /**
     * Stock of this seckill activity.
     */
    private Integer stock;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    /**
     * Status of seckill activity.
     */
    private Integer status;
}


