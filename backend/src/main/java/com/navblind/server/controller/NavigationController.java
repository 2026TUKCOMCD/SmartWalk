package com.navblind.server.controller;

import com.navblind.server.dto.RouteDto.*;
import com.navblind.server.entity.NavigationSession;
import com.navblind.server.integration.OsrmClient;
import com.navblind.server.service.NavigationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/navigation")
@RequiredArgsConstructor
@Slf4j
public class NavigationController {

    private final NavigationService navigationService;
    private final OsrmClient osrmClient;

    /**
     * 경로 탐색 (POST /v1/navigation/route)
     */
    @PostMapping("/route")
    public ResponseEntity<RouteResponse> calculateRoute(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @Valid @RequestBody RouteRequest request) {

        // For demo purposes, use a default user if no user ID provided
        if (userId == null) {
            userId = getOrCreateDemoUserId();
        }

        log.info("Route request from user {}: {} -> {}", userId,
                request.getOriginLat() + "," + request.getOriginLng(),
                request.getDestLat() + "," + request.getDestLng());

        RouteResponse response = navigationService.calculateRoute(userId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 경로 재탐색 (POST /v1/navigation/reroute)
     */
    @PostMapping("/reroute")
    public ResponseEntity<RouteResponse> reroute(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @Valid @RequestBody RerouteRequest request) {

        if (userId == null) {
            userId = getOrCreateDemoUserId();
        }

        log.info("Reroute request from user {} for session {}", userId, request.getSessionId());

        RouteResponse response = navigationService.reroute(userId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 네비게이션 이력 조회 (GET /v1/navigation/sessions)
     */
    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> getNavigationHistory(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {

        if (userId == null) {
            userId = getOrCreateDemoUserId();
        }

        int page = offset / Math.max(limit, 1);
        Page<NavigationSession> sessions = navigationService.getNavigationHistory(userId, page, limit);

        List<NavigationSessionResponse> sessionResponses = sessions.getContent().stream()
                .map(this::toSessionResponse)
                .toList();

        return ResponseEntity.ok(Map.of(
                "sessions", sessionResponses,
                "total", sessions.getTotalElements(),
                "limit", limit,
                "offset", offset
        ));
    }

    /**
     * 세션 상태 업데이트 (PATCH /v1/navigation/sessions/{sessionId})
     */
    @PatchMapping("/sessions/{sessionId}")
    public ResponseEntity<NavigationSessionResponse> updateSession(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID sessionId,
            @RequestBody UpdateSessionRequest request) {

        if (userId == null) {
            userId = getOrCreateDemoUserId();
        }

        NavigationSession.SessionStatus newStatus = switch (request.getStatus()) {
            case "COMPLETED" -> NavigationSession.SessionStatus.COMPLETED;
            case "CANCELLED" -> NavigationSession.SessionStatus.CANCELLED;
            default -> throw new IllegalArgumentException("Invalid status: " + request.getStatus());
        };

        navigationService.updateSessionStatus(userId, sessionId, newStatus);

        // Return updated session (simplified for demo)
        return ResponseEntity.ok(NavigationSessionResponse.builder()
                .id(sessionId)
                .status(newStatus.name())
                .build());
    }

    /**
     * 좌표를 가장 가까운 도로에 snap (GET /v1/navigation/nearest)
     * VPS/GPS 좌표를 OSM 도로망에 정합하는 데 사용됩니다.
     */
    @GetMapping("/nearest")
    public ResponseEntity<NearestResponse> getNearestRoad(
            @RequestParam double lat,
            @RequestParam double lng) {

        log.debug("Nearest road request for: {}, {}", lat, lng);

        OsrmClient.NearestResult result = osrmClient.getNearestRoad(lat, lng, 1);

        if (result == null) {
            return ResponseEntity.notFound().build();
        }

        NearestResponse response = NearestResponse.builder()
                .originalLat(result.getOriginalLat())
                .originalLng(result.getOriginalLng())
                .snappedLat(result.getSnappedLat())
                .snappedLng(result.getSnappedLng())
                .distance(result.getDistance())
                .roadName(result.getRoadName())
                .isOnRoad(result.getDistance() < 15.0) // 15m 이내면 도로 위로 판단
                .build();

        return ResponseEntity.ok(response);
    }

    private NavigationSessionResponse toSessionResponse(NavigationSession session) {
        return NavigationSessionResponse.builder()
                .id(session.getId())
                .destName(session.getDestName())
                .status(session.getStatus().name())
                .distance(session.getDistanceMeters())
                .startedAt(session.getStartedAt() != null ? session.getStartedAt().toString() : null)
                .completedAt(session.getCompletedAt() != null ? session.getCompletedAt().toString() : null)
                .rerouteCount(session.getRerouteCount())
                .build();
    }

    // Demo user ID - in production, this would come from authentication
    private static final UUID DEMO_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private UUID getOrCreateDemoUserId() {
        return DEMO_USER_ID;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UpdateSessionRequest {
        private String status;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class NavigationSessionResponse {
        private UUID id;
        private String destName;
        private String status;
        private Integer distance;
        private String startedAt;
        private String completedAt;
        private Integer rerouteCount;
    }

    /**
     * Nearest API 응답 DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class NearestResponse {
        /** 원래 요청한 위도 */
        private Double originalLat;
        /** 원래 요청한 경도 */
        private Double originalLng;
        /** 도로에 snap된 위도 */
        private Double snappedLat;
        /** 도로에 snap된 경도 */
        private Double snappedLng;
        /** 원래 좌표에서 snap된 좌표까지의 거리 (미터) */
        private Double distance;
        /** 도로 이름 (있는 경우) */
        private String roadName;
        /** 도로 위에 있는지 여부 (distance < 15m) */
        private Boolean isOnRoad;
    }
}
