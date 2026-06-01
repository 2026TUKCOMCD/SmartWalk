package com.navblind.server.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.navblind.server.config.FirebaseProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

public class FirebaseAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final FirebaseProperties props;

    public FirebaseAuthFilter(FirebaseProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // Firebase 비활성화 모드 (개발/테스트) — X-User-Id 헤더로 인증 대체
        if (props.disabled()) {
            String userId = request.getHeader("X-User-Id");
            if (StringUtils.hasText(userId)) {
                setAuthentication(userId);
            }
            chain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(token);
            setAuthentication(decoded.getUid());
        } catch (Exception e) {
            log.warn("Firebase token verification failed: {}", e.getMessage());
        }

        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private void setAuthentication(String uid) {
        var auth = new UsernamePasswordAuthenticationToken(uid, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
