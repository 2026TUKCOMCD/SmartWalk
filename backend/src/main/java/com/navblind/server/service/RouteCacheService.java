package com.navblind.server.service;

import com.navblind.server.integration.OsrmClient;
import com.navblind.server.integration.OsrmClient.OsrmRouteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * OSRM 경로 결과를 Redis에 10분간 캐시한다.
 * 같은 출발지-목적지 쌍이 짧은 시간 안에 반복 요청되면 OSRM 호출을 절약할 수 있다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RouteCacheService {

    private static final String KEY_PREFIX = "route:";
    private static final Duration TTL = Duration.ofMinutes(10);
    // 좌표 반올림 자릿수: 소수점 4자리 ≈ 11m 오차 (캐시 히트율과 정확도의 균형)
    private static final int COORD_SCALE = 4;

    private final OsrmClient osrmClient;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 캐시를 먼저 확인하고 없으면 OSRM을 호출한다.
     */
    public OsrmRouteResult getRoute(double originLat, double originLng,
                                    double destLat, double destLng) {
        String key = buildKey(originLat, originLng, destLat, destLng);

        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof OsrmRouteResult result) {
            log.debug("Route cache hit: {}", key);
            return result;
        }

        log.debug("Route cache miss: {}", key);
        OsrmRouteResult result = osrmClient.getRoute(originLat, originLng, destLat, destLng);
        if (result != null) {
            redisTemplate.opsForValue().set(key, result, TTL);
        }
        return result;
    }

    /**
     * 특정 경로의 캐시를 수동으로 무효화한다 (경로 변경 이벤트 등).
     */
    public void evict(double originLat, double originLng,
                      double destLat, double destLng) {
        String key = buildKey(originLat, originLng, destLat, destLng);
        redisTemplate.delete(key);
        log.debug("Route cache evicted: {}", key);
    }

    private String buildKey(double originLat, double originLng,
                             double destLat, double destLng) {
        return KEY_PREFIX
            + round(originLat) + "," + round(originLng) + ":"
            + round(destLat) + "," + round(destLng);
    }

    private double round(double value) {
        double scale = Math.pow(10, COORD_SCALE);
        return Math.round(value * scale) / scale;
    }
}
