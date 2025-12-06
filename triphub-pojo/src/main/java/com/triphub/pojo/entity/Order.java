package com.triphub.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("`order`")
public class Order {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long userId;

    private Long tripId;

    private Long seckillActivityId;

    /**
     * 0 pending, 1 paid, 2 canceled, 3 ongoing, 4 finished.
     */
    private Integer status;

    private BigDecimal amount;

    private LocalDateTime orderTime;

    private LocalDateTime payTime;

    private LocalDateTime cancelTime;
}


