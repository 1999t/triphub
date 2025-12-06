package com.triphub.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.triphub.pojo.entity.UserProfile;

public interface UserProfileService extends IService<UserProfile> {

    UserProfile getByUserId(Long userId);

    void saveOrUpdateProfile(Long userId, String profileJson);
}



