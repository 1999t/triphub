package com.triphub.server.interceptor;

import com.triphub.common.context.BaseContext;
import com.triphub.common.properties.JwtProperties;
import com.triphub.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtTokenAdminInterceptor implements HandlerInterceptor {

    private final JwtProperties jwtProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String tokenName = jwtProperties.getAdminTokenName();
        String token = request.getHeader(tokenName);
        if (token == null || token.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getAdminSecretKey(), token);
            Object id = claims.get("adminId");
            if (id != null) {
                Long adminId = Long.valueOf(id.toString());
                BaseContext.setCurrentId(adminId);
                // 管理端同样将 ID 写入 MDC，这里沿用 userId key，便于统一检索
                MDC.put("userId", String.valueOf(adminId));
            }
            return true;
        } catch (Exception e) {
            log.warn("管理员 JWT 校验失败: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        BaseContext.clear();
    }
}


