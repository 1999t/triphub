package com.triphub.server.filter;

import com.triphub.common.context.BaseContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * 请求级别的统一日志过滤器：
 * - 为每次 HTTP 请求生成或透传 traceId；
 * - 将 traceId 写入 MDC，便于后续所有日志统一串联；
 * - 记录一次完整请求的关键信息：traceId、userId、方法、URI、HTTP 状态码与耗时。
 *
 * 说明：
 * - 如果上游已经在 Header 中携带 X-Trace-Id，则直接透传使用，便于与网关链路打通；
 * - userId 由 JWT 拦截器在后续流程中写入 MDC（key="userId"），本过滤器只负责统一输出。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class TraceLoggingFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TRACE_ID_KEY = "traceId";
    private static final String USER_ID_KEY = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();

        // 1. 生成或透传 traceId
        String incomingTraceId = request.getHeader(TRACE_ID_HEADER);
        String traceId = StringUtils.hasText(incomingTraceId) ? incomingTraceId : generateTraceId();
        MDC.put(TRACE_ID_KEY, traceId);
        // 方便下游及客户端查看，同步写回响应头
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            // 2. 打一条请求开始日志（不记录 Body，避免日志过大）
            log.info("HTTP 请求开始: traceId={}, method={}, uri={}, remoteIp={}",
                    traceId, request.getMethod(), request.getRequestURI(), request.getRemoteAddr());

            filterChain.doFilter(request, response);
        } finally {
            // 3. 统一记录请求结束日志
            long duration = System.currentTimeMillis() - start;
            Long userId = BaseContext.getCurrentId();
            String mdcUserId = MDC.get(USER_ID_KEY);
            // 优先使用 MDC 中的 userId，如为空再回退到 BaseContext 值
            String finalUserId = StringUtils.hasText(mdcUserId)
                    ? mdcUserId
                    : (userId == null ? "null" : String.valueOf(userId));

            log.info("HTTP 请求结束: traceId={}, userId={}, method={}, uri={}, status={}, durationMs={}",
                    traceId, finalUserId, request.getMethod(), request.getRequestURI(),
                    response.getStatus(), duration);

            // 4. 清理 MDC，避免线程复用导致数据串线
            MDC.remove(TRACE_ID_KEY);
            MDC.remove(USER_ID_KEY);
        }
    }

    /**
     * 简单生成一个无连字符的 UUID 作为 traceId。
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}


