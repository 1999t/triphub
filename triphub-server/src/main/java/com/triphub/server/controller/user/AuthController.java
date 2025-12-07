package com.triphub.server.controller.user;

import com.triphub.common.result.Result;
import com.triphub.pojo.dto.UserLoginDTO;
import com.triphub.server.limit.SimpleRateLimiter;
import com.triphub.server.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/user/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final SimpleRateLimiter rateLimiter;

    /**
     * 模拟发送验证码接口：直接返回固定验证码，方便联调。
     * 同时对接口做简单限流，防止被恶意脚本高频调用。
     */
    @PostMapping("/sendCode")
    public Result<String> sendCode(@RequestBody UserLoginDTO dto, HttpServletRequest request) {
        if (dto == null || !StringUtils.hasText(dto.getPhone())) {
            return Result.error("手机号不能为空");
        }
        String phone = dto.getPhone();
        String ip = request.getRemoteAddr();

        // 1. 按 IP 限流：同一 IP 1 分钟内最多请求 20 次
        boolean ipAllowed = rateLimiter.tryAcquire("sendCode:ip", ip, 60, 20);
        // 2. 按手机号限流：同一手机号 1 小时内最多请求 5 次
        boolean phoneAllowed = rateLimiter.tryAcquire("sendCode:phone", phone, 3600, 5);
        if (!ipAllowed || !phoneAllowed) {
            return Result.error("请求过于频繁，请稍后再试");
        }

        // 实际项目这里会发短信，这里简化为固定验证码 1234
        return Result.success("1234");
    }

    /**
     * 手机号 + 验证码登录，返回 JWT token
     */
    @PostMapping("/login")
    public Result<String> login(@RequestBody UserLoginDTO dto) {
        String token = userService.loginByPhone(dto.getPhone(), dto.getCode());
        return Result.success(token);
    }
}


