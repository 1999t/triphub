package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.triphub.common.exception.BaseException;
import com.triphub.common.properties.JwtProperties;
import com.triphub.common.utils.JwtUtil;
import com.triphub.pojo.entity.User;
import com.triphub.server.mapper.UserMapper;
import com.triphub.server.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final JwtProperties jwtProperties;

    @Override
    public String loginByPhone(String phone, String code) {
        if (!StringUtils.hasText(phone) || !StringUtils.hasText(code)) {
            throw new BaseException("手机号或验证码不能为空");
        }
        // 这里只做简单校验：固定验证码 1234，后续可接短信服务
        if (!"1234".equals(code)) {
            throw new BaseException("验证码错误");
        }
        // 查询或创建用户
        User user = lambdaQuery().eq(User::getPhone, phone).one();
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setNickname("用户" + phone.substring(Math.max(0, phone.length() - 4)));
            save(user);
        }
        // 生成 JWT
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        return JwtUtil.createJWT(jwtProperties.getUserSecretKey(), jwtProperties.getUserTtl(), claims);
    }
}


