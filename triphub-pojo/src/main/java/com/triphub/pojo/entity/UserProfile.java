package com.triphub.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * User profile entity, stores flexible JSON string for persona fields.
 */
@Data
@TableName("user_profile")
public class UserProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /**
     * User persona JSON string, e.g. interests, budget, duration preferences.
     */
    private String profileJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}



