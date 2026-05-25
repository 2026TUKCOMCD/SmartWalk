package com.navblind.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * 경로 요청 엔드포인트에 분당 30회 요청 제한을 적용한다.
 * Redis 카운터를 사용하므로 다중 인스턴스에서도 일관성이 유지된다.
 */
@Configuration
@Slf4j
public class RateLimitConfig {

    private static final int MAX_REQUESTS_PER_MINUTE = 30;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Bean
    public OncePerRequestFilter rateLimitFilter(RedisTemplate<String, Object> redisTemplate) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req,
                                            HttpServletResponse res,
                                            FilterChain chain) throws ServletException, IOException {
                String uri = req.getRequestURI();
                if (isRateLimited(uri)) {
                    String userId = req.getHeader("X-User-Id");
                    String key = "ratelimit:" + (userId != null ? userId : req.getRemoteAddr()) + ":" + uri;

                    Long count = redisTemplate.opsForValue().increment(key);
                    if (count == 1) redisTemplate.expire(key, WINDOW);

                    if (count != null && count > MAX_REQUESTS_PER_MINUTE) {
                        log.warn("Rate limit exceeded: key={}", key);
                        res.setStatus(429);
                        res.setContentType("application/json");
                        res.getWriter().write("{\"error\":\"Too many requests\",\"retryAfter\":60}");
                        return;
                    }
                }
                chain.doFilter(req, res);
            }

            private boolean isRateLimited(String uri) {
                return uri.contains("/navigation/route") || uri.contains("/navigation/reroute");
            }
        };
    }
}
