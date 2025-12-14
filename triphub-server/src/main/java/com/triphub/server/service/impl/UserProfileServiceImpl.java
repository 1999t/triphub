package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.triphub.pojo.entity.UserProfile;
import com.triphub.server.mapper.UserProfileMapper;
import com.triphub.server.service.UserProfileService;
import org.springframework.stereotype.Service;

/**
 * 用户画像服务实现，基于 user_id 做简单的查询与保存。
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
        // 直接走 DB Upsert，消除并发下 “先查再插/更” 的竞态
        baseMapper.upsertProfile(userId, profileJson);
    }
}



