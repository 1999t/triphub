package com.triphub.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.triphub.pojo.entity.UserProfile;

/**
 * 用户画像服务接口。
 * User profile service.
 *
 * 提供按 userId 查询与保存/更新画像 JSON 的能力。
 */
public interface UserProfileService extends IService<UserProfile> {

    /**
     * 根据用户 ID 查询画像。
     * Get user profile by userId.
     */
    UserProfile getByUserId(Long userId);

    /**
     * 保存或更新用户画像 JSON。
     * Save or update user profile JSON content.
     */
    void saveOrUpdateProfile(Long userId, String profileJson);
}



