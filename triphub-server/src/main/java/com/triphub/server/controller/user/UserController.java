package com.triphub.server.controller.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.context.BaseContext;
import com.triphub.common.result.Result;
import com.triphub.pojo.entity.User;
import com.triphub.pojo.entity.UserProfile;
import com.triphub.server.service.UserService;
import com.triphub.server.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/user/profile")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;

    /**
     * 当前登录用户信息，依赖 JWT + BaseContext
     */
    @GetMapping("/me")
    public Result<User> currentUser() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        User user = userService.getById(userId);
        return Result.success(user);
    }

    /**
     * Get current user profile (persona) JSON.
     */
    @GetMapping
    public Result<Map<String, Object>> getUserProfile() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        UserProfile profile = userProfileService.getByUserId(userId);
        if (profile == null || profile.getProfileJson() == null) {
            return Result.success(Collections.emptyMap());
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(profile.getProfileJson(), Map.class);
            return Result.success(map);
        } catch (JsonProcessingException e) {
            // If stored JSON is broken, just return empty profile to client.
            return Result.success(Collections.emptyMap());
        }
    }

    /**
     * Setup or update current user profile (persona).
     * Body is a flexible JSON object, will be stored as-is.
     */
    @PostMapping
    public Result<Void> saveUserProfile(@RequestBody Map<String, Object> body) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        try {
            String json = objectMapper.writeValueAsString(body == null ? Collections.emptyMap() : body);
            userProfileService.saveOrUpdateProfile(userId, json);
            return Result.success(null);
        } catch (JsonProcessingException e) {
            return Result.error("画像数据格式错误");
        }
    }
}


