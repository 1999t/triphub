package com.triphub.server.config;

import com.triphub.server.interceptor.JwtTokenAdminInterceptor;
import com.triphub.server.interceptor.JwtTokenUserInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final JwtTokenAdminInterceptor adminInterceptor;
    private final JwtTokenUserInterceptor userInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("注册 JWT 拦截器...");

        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/auth/login");

        registry.addInterceptor(userInterceptor)
                .addPathPatterns("/user/**")
                .excludePathPatterns("/user/auth/login")
                .excludePathPatterns("/user/auth/sendCode");
    }
}


