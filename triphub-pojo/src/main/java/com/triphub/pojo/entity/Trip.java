package com.triphub.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("trip")
public class Trip {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String destinationCity;

    private LocalDate startDate;

    private LocalDate endDate;

    private Integer days;

    /**
     * 0=私有，1=好友可见，2=公开
     */
    private Integer visibility;

    private Integer likeCount;

    private Integer viewCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}


