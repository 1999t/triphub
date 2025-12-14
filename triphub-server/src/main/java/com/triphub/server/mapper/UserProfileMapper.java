package com.triphub.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.triphub.pojo.entity.UserProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    /**
     * 基于 user_id 唯一索引做原子 Upsert，避免并发下的 “先查再插” 竞态。
     *
     * 注意：
     * - user_profile.profile_json 为 MySQL JSON 类型，这里直接传入 JSON 字符串即可（无效 JSON 会被 DB 拒绝）
     * - update_time 由 ON DUPLICATE KEY UPDATE 强制刷新
     */
    @Insert("INSERT INTO user_profile(user_id, profile_json) " +
            "VALUES (#{userId}, #{profileJson}) " +
            "ON DUPLICATE KEY UPDATE " +
            "profile_json = VALUES(profile_json), " +
            "update_time = CURRENT_TIMESTAMP")
    int upsertProfile(@Param("userId") Long userId, @Param("profileJson") String profileJson);
}



