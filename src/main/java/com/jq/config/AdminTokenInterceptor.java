package com.jq.config;

import com.jq.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminTokenInterceptor implements HandlerInterceptor {

    private static final String HEADER = "X-Admin-Token";

    private final String adminToken;

    public AdminTokenInterceptor(@Value("${jobqueue.admin.token}") String adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader(HEADER);
        if (token == null || !sameToken(token, adminToken)) {
            throw new UnauthorizedException("missing or invalid " + HEADER + " header");
        }
        return true;
    }

    // constant time compare
    private boolean sameToken(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
