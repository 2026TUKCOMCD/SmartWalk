package com.navblind.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 활성 네비게이션 세션 상태를 Redis에 캐시한다.
 * 서버 재시작 없이 빠른 세션 유효성 검사를 제공한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionCacheService {

    private static final String KEY_PREFIX = "session:active:";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redisTemplate;

    public void markActive(UUID sessionId, UUID userId) {
        String key = KEY_PREFIX + sessionId;
        redisTemplate.opsForValue().set(key, userId.toString(), TTL);
        log.debug("Session marked active: {}", sessionId);
    }

    public boolean isActive(UUID sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + sessionId));
    }

    public void invalidate(UUID sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
        log.debug("Session invalidated: {}", sessionId);
    }

    public void extendTtl(UUID sessionId) {
        redisTemplate.expire(KEY_PREFIX + sessionId, TTL);
    }
}
