package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.triphub.pojo.entity.UserProfile;
import com.triphub.server.mapper.UserProfileMapper;
import com.triphub.server.service.UserProfileService;
import org.springframework.stereotype.Service;

/**
 * Simple user profile service based on user_id lookup.
 */
@Service
public class UserProfileServiceImpl extends ServiceImpl<UserProfileMapper, UserProfile> implements UserProfileService {

    @Override
    public UserProfile getByUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        return lambdaQuery()
                .eq(UserProfile::getUserId, userId)
                .one();
    }

    @Override
    public void saveOrUpdateProfile(Long userId, String profileJson) {
        if (userId == null) {
            return;
        }
        UserProfile profile = getByUserId(userId);
        if (profile == null) {
            profile = new UserProfile();
            profile.setUserId(userId);
            profile.setProfileJson(profileJson);
            save(profile);
        } else {
            profile.setProfileJson(profileJson);
            updateById(profile);
        }
    }
}



