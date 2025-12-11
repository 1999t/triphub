package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.triphub.common.constant.RedisConstants;
import com.triphub.common.exception.BaseException;
import com.triphub.common.properties.JwtProperties;
import com.triphub.common.result.ErrorCode;
import com.triphub.common.utils.JwtUtil;
import com.triphub.pojo.entity.User;
import com.triphub.server.mapper.UserMapper;
import com.triphub.server.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final JwtProperties jwtProperties;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public String loginByPhone(String phone, String code) {
        if (!StringUtils.hasText(phone) || !StringUtils.hasText(code)) {
            throw new BaseException(ErrorCode.AUTH_INVALID_PARAM);
        }
        // 从 Redis 校验验证码
        String redisKey = RedisConstants.LOGIN_CODE_KEY + phone;
        String cacheCode = stringRedisTemplate.opsForValue().get(redisKey);
        if (cacheCode == null) {
            throw new BaseException(ErrorCode.AUTH_CODE_EXPIRED);
        }
        if (!cacheCode.equals(code)) {
            throw new BaseException(ErrorCode.AUTH_CODE_ERROR);
        }
        // 验证通过后删除验证码，防止重放
        stringRedisTemplate.delete(redisKey);

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
        String token = JwtUtil.createJWT(jwtProperties.getUserSecretKey(), jwtProperties.getUserTtl(), claims);

        // 将登录态写入 Redis，便于服务端控制 token 失效
        String loginKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForValue().set(
                loginKey,
                String.valueOf(user.getId()),
                jwtProperties.getUserTtl(),
                TimeUnit.MILLISECONDS
        );

        return token;
    }
}


