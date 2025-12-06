package com.triphub.server.controller.user;

import com.triphub.common.result.Result;
import com.triphub.pojo.dto.UserLoginDTO;
import com.triphub.server.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * 模拟发送验证码接口：直接返回固定验证码，方便联调
     */
    @PostMapping("/sendCode")
    public Result<String> sendCode(@RequestBody UserLoginDTO dto) {
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


