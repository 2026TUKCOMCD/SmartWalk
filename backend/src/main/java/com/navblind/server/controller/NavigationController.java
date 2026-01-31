package com.navblind.server.controller;

import com.navblind.server.dto.RouteDto.*;
import com.navblind.server.entity.NavigationSession;
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
}
