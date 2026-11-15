package com.trace.recall.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 外部处置系统凭证拦截器：/external/** 必须携带 X-API-Key 且与配置一致。
 */
@Component
public class ExternalApiKeyInterceptor implements HandlerInterceptor {

    private final String expectedKey;

    public ExternalApiKeyInterceptor(@Value("${app.external.api-key}") String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String key = request.getHeader("X-API-Key");
        if (key != null && key.equals(expectedKey)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"UNAUTHORIZED: 缺少或错误的 X-API-Key\"}");
        return false;
    }
}
