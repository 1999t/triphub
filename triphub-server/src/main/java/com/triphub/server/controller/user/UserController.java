package com.triphub.server.controller.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triphub.common.context.BaseContext;
import com.triphub.common.result.Result;
import com.triphub.pojo.entity.User;
import com.triphub.pojo.entity.UserProfile;
import com.triphub.server.service.UserService;
import com.triphub.server.service.UserProfileService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
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

    /**
     * 用户画像 JSON 体积上限（字节）。
     * KISS：先用一个硬上限挡住“把画像当垃圾桶”的风险。
     */
    private static final int MAX_PROFILE_JSON_BYTES = 8 * 1024;

    private final UserService userService;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;

    /**
     * 当前登录用户信息，依赖 JWT + BaseContext 解析出的用户 ID。
     */
    @GetMapping("/me")
    public Result<User> currentUser() {
        Long userId = BaseContext.getCurrentId();
        User user = userService.getById(userId);
        return Result.success(user);
    }

    /**
     * 获取当前用户画像 JSON（用于 AI 推荐及个性化展示）。
     */
    @GetMapping
    public Result<UserProfileResponse> getUserProfile() {
        Long userId = BaseContext.getCurrentId();
        UserProfile profile = userProfileService.getByUserId(userId);
        if (profile == null || profile.getProfileJson() == null) {
            return Result.success(new UserProfileResponse(Collections.emptyMap(), null));
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(profile.getProfileJson(), Map.class);
            return Result.success(new UserProfileResponse(map, profile.getUpdateTime()));
        } catch (JsonProcessingException e) {
            // 如果存储的 JSON 已损坏，不抛出异常，直接返回空画像，避免影响接口可用性。
            return Result.success(new UserProfileResponse(Collections.emptyMap(), profile.getUpdateTime()));
        }
    }

    /**
     * 设置或更新当前用户画像（Setup 问卷 + 后续修改）。
     * Setup or update current user profile (persona).
     * 请求体为任意 JSON 对象（字段可自由扩展），会被整体序列化后存入 user_profile.profile_json。
     *
     * 语义说明（面试很加分）：
     * - 当前接口为 Replace（整份覆盖写），不是 Merge（部分字段合并）。
     * - 如果要做部分更新，建议新增 PATCH /user/profile 做 Merge，并明确冲突策略。
     */
    @PostMapping
    public Result<Void> saveUserProfile(@RequestBody Map<String, Object> body) {
        Long userId = BaseContext.getCurrentId();
        try {
            String json = objectMapper.writeValueAsString(body == null ? Collections.emptyMap() : body);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PROFILE_JSON_BYTES) {
                return Result.error("画像过大，请精简后再保存");
            }
            userProfileService.saveOrUpdateProfile(userId, json);
            return Result.success(null);
        } catch (JsonProcessingException e) {
            return Result.error("画像数据格式错误");
        }
    }

    /**
     * 部分更新（Merge）用户画像。
     *
     * KISS 版本：只做 top-level 字段合并（incoming 覆盖同名 key），不做深度合并。
     * - 想删除字段：传 null（该 key 会被写成 null）
     * - 想做深度合并：建议客户端提交完整对象，或后续引入明确的深合并策略
     */
    @PatchMapping
    public Result<Void> patchUserProfile(@RequestBody Map<String, Object> patch) {
        Long userId = BaseContext.getCurrentId();
        Map<String, Object> base = new HashMap<>();
        UserProfile existing = userProfileService.getByUserId(userId);
        if (existing != null && existing.getProfileJson() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(existing.getProfileJson(), Map.class);
                if (parsed != null) {
                    base.putAll(parsed);
                }
            } catch (Exception ignore) {
                // DB 里历史 JSON 若损坏，则按空画像处理，保证接口可用性
            }
        }
        if (patch != null) {
            base.putAll(patch);
        }
        try {
            String json = objectMapper.writeValueAsString(base);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PROFILE_JSON_BYTES) {
                return Result.error("画像过大，请精简后再保存");
            }
            userProfileService.saveOrUpdateProfile(userId, json);
            return Result.success(null);
        } catch (JsonProcessingException e) {
            return Result.error("画像数据格式错误");
        }
    }

    @Data
    @AllArgsConstructor
    public static class UserProfileResponse {
        /** 画像 JSON（动态 schema） */
        private Map<String, Object> profile;
        /** 版本信息：以更新时间作为“弱版本号” */
        private LocalDateTime updateTime;
    }
}


