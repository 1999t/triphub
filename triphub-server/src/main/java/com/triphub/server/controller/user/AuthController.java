package com.triphub.server.controller.user;

import com.triphub.common.result.Result;
import com.triphub.common.constant.RedisConstants;
import com.triphub.common.result.ErrorCode;
import com.triphub.pojo.dto.UserLoginDTO;
import com.triphub.server.limit.SimpleRateLimiter;
import com.triphub.server.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/user/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final SimpleRateLimiter rateLimiter;

    private final StringRedisTemplate stringRedisTemplate;

    /** 简单的大陆手机号码正则校验，可按需要调整 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");

    /**
     * 模拟发送验证码接口：直接返回固定验证码，方便联调。
     * 同时对接口做简单限流，防止被恶意脚本高频调用。
     */
    @PostMapping("/sendCode")
    public Result<String> sendCode(@RequestBody UserLoginDTO dto, HttpServletRequest request) {
        if (dto == null || !StringUtils.hasText(dto.getPhone())) {
            return Result.error(ErrorCode.AUTH_INVALID_PARAM);
        }
        String phone = dto.getPhone();
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            return Result.error(ErrorCode.AUTH_INVALID_PHONE);
        }
        String ip = request.getRemoteAddr();

        // 1. 按 IP 限流：同一 IP 1 分钟内最多请求 20 次
        boolean ipAllowed = rateLimiter.tryAcquire("sendCode:ip", ip, 60, 20);
        // 2. 按手机号限流：同一手机号 1 小时内最多请求 5 次
        boolean phoneAllowed = rateLimiter.tryAcquire("sendCode:phone", phone, 3600, 5);
        if (!ipAllowed || !phoneAllowed) {
            return Result.error(ErrorCode.AUTH_CODE_TOO_FREQUENT);
        }

        // 生成 6 位数字验证码并写入 Redis，设置短 TTL
        int codeInt = (int) (Math.random() * 1_000_000);
        String code = String.format("%06d", codeInt);
        String redisKey = RedisConstants.LOGIN_CODE_KEY + phone;
        stringRedisTemplate.opsForValue().set(
                redisKey,
                code,
                RedisConstants.LOGIN_CODE_TTL,
                TimeUnit.MINUTES
        );

        // 真实场景应通过短信服务下发，这里为了联调方便仍然直接返回验证码
        return Result.success(code);
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


