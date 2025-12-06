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

/**
 * 用户相关接口：
 * - 查询当前登录用户基础信息；
 * - 设置 / 获取当前用户画像 JSON（用于 Setup 阶段问卷与后续更新）。
 */
@RestController
@RequestMapping("/user/profile")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;

    /**
     * 当前登录用户信息，依赖 JWT + BaseContext 解析出的用户 ID。
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
     * 获取当前用户画像 JSON（用于 AI 推荐及个性化展示）。
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
            // 如果存储的 JSON 已损坏，不抛出异常，直接返回空画像，避免影响接口可用性。
            return Result.success(Collections.emptyMap());
        }
    }

    /**
     * 设置或更新当前用户画像（Setup 问卷 + 后续修改）。
     * Setup or update current user profile (persona).
     * 请求体为任意 JSON 对象（字段可自由扩展），会被整体序列化后存入 user_profile.profile_json。
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


