package com.triphub.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户画像实体，对应 user_profile 表。
 * User profile entity, stores flexible JSON string for persona fields.
 *
 * 这里不做字段级拆表，而是通过一个 JSON 串承载可变的画像字段（兴趣标签、预算、出行方式等），
 * 方便后续频繁调整画像结构。
 */
@Data
@TableName("user_profile")
public class UserProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /**
     * 用户画像 JSON 字符串，例如兴趣标签、预算区间、出行天数偏好等。
     * User persona JSON string, e.g. interests, budget, duration preferences.
     */
    private String profileJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}



