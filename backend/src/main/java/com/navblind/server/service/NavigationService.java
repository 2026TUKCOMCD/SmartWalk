package com.navblind.server.service;

import com.navblind.server.dto.RouteDto.*;
import com.navblind.server.entity.NavigationSession;
import com.navblind.server.entity.User;
import com.navblind.server.integration.OsrmClient;
import com.navblind.server.integration.OsrmClient.OsrmRouteResult;
import com.navblind.server.repository.NavigationSessionRepository;
import com.navblind.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NavigationService {

    private final OsrmClient osrmClient;
    private final NavigationSessionRepository sessionRepository;
    private final UserRepository userRepository;

    //경로 계산 기능을 수행
    //@Transcational: 트랜잭션 롤백 기능
    @Transactional
    public RouteResponse calculateRoute(UUID userId, RouteRequest request) {
        log.info("Calculating route for user {} from ({}, {}) to ({}, {})",
                userId, request.getOriginLat(), request.getOriginLng(),
                request.getDestLat(), request.getDestLng());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // Cancel any existing active session
        sessionRepository.findActiveSession(userId).ifPresent(session -> {
            session.cancel();
            sessionRepository.save(session);
            log.info("Cancelled existing active session: {}", session.getId());
        });

        // Call OSRM for route calculation
        OsrmRouteResult osrmResult = osrmClient.getRoute(
                request.getOriginLat(), request.getOriginLng(),
                request.getDestLat(), request.getDestLng()
        );

        if (osrmResult == null) {
            log.warn("OSRM returned no route for request");
            throw new RouteNotFoundException("경로를 찾을 수 없습니다");
        }

        // Create navigation session
        NavigationSession session = NavigationSession.builder()
                .user(user)
                .originLat(request.getOriginLat())
                .originLng(request.getOriginLng())
                .destLat(request.getDestLat())
                .destLng(request.getDestLng())
                .destName(request.getDestName() != null ? request.getDestName() : "목적지")
                .distanceMeters(osrmResult.getDistance())
                .build();

        session = sessionRepository.save(session);
        log.info("Created navigation session: {}", session.getId());

        return RouteResponse.builder()
                .sessionId(session.getId())
                .distance(osrmResult.getDistance())
                .duration(osrmResult.getDuration())
                .waypoints(osrmResult.getWaypoints())
                .instructions(osrmResult.getInstructions())
                .build();
    }
    //경로 재계산 기능을 수행
    @Transactional
    public RouteResponse reroute(UUID userId, RerouteRequest request) {
        log.info("Rerouting for user {} session {} from ({}, {})",
                userId, request.getSessionId(), request.getCurrentLat(), request.getCurrentLng());

        NavigationSession session = sessionRepository.findByIdAndUserId(request.getSessionId(), userId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + request.getSessionId()));

        if (session.getStatus() != NavigationSession.SessionStatus.ACTIVE) {
            throw new IllegalStateException("Session is not active");
        }

        // Call OSRM for new route from current position to destination
        OsrmRouteResult osrmResult = osrmClient.getRoute(
                request.getCurrentLat(), request.getCurrentLng(),
                session.getDestLat(), session.getDestLng()
        );

        if (osrmResult == null) {
            log.warn("OSRM returned no route for reroute request");
            throw new RouteNotFoundException("새로운 경로를 찾을 수 없습니다");
        }

        // Update session
        session.incrementRerouteCount();
        session.setDistanceMeters(osrmResult.getDistance());
        sessionRepository.save(session);

        log.info("Reroute complete for session {}, reroute count: {}",
                session.getId(), session.getRerouteCount());

        return RouteResponse.builder()
                .sessionId(session.getId())
                .distance(osrmResult.getDistance())
                .duration(osrmResult.getDuration())
                .waypoints(osrmResult.getWaypoints())
                .instructions(osrmResult.getInstructions())
                .build();
    }

    //도착하거나 안내가 취소되었을 때 호출되어 세션에 대해 처리하고 DB에 저장
    @Transactional
    public void updateSessionStatus(UUID userId, UUID sessionId, NavigationSession.SessionStatus newStatus) {
        NavigationSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        switch (newStatus) {
            case COMPLETED -> session.complete();
            case CANCELLED -> session.cancel();
            case FAILED -> session.fail();
            default -> throw new IllegalArgumentException("Invalid status transition: " + newStatus);
        }

        sessionRepository.save(session);
        log.info("Updated session {} status to {}", sessionId, newStatus);
    }

    //지금 안내 중인게 있는 지 확인하는 용도로 쓰이는 함수
    public Optional<NavigationSession> getActiveSession(UUID userId) {
        return sessionRepository.findActiveSession(userId);
    }

    //안내 기록을 열람하는 데 사용하는 함수
    public Page<NavigationSession> getNavigationHistory(UUID userId, int page, int size) {
        return sessionRepository.findByUserIdOrderByStartedAtDesc(userId, PageRequest.of(page, size));
    }

    public static class RouteNotFoundException extends RuntimeException {
        public RouteNotFoundException(String message) {
            super(message);
        }
    }
}
