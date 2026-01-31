package com.navblind.server.controller;

import com.navblind.server.dto.DestinationDto.*;
import com.navblind.server.service.DestinationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/destinations")
@RequiredArgsConstructor
@Slf4j
public class DestinationController {

    private final DestinationService destinationService;

    /**
     * 목적지 검색 (GET /v1/destinations/search)
     * OSM Nominatim을 사용하여 장소를 검색합니다.
     */
    @GetMapping("/search")
    public ResponseEntity<SearchResponse> searchDestinations(
            @RequestParam @Size(min = 2) String query,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "10") @Min(1) int limit) {

        log.info("Search request: query='{}', location=({}, {}), limit={}",
                query, lat, lng, limit);

        List<SearchResult> results = destinationService.searchPlaces(query, lat, lng, Math.min(limit, 50));

        return ResponseEntity.ok(SearchResponse.builder()
                .results(results)
                .build());
    }

    /**
     * 저장된 목적지 목록 조회 (GET /v1/destinations)
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getDestinations(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @RequestParam(required = false) String label,
            @RequestParam(defaultValue = "useCount") String sort) {

        if (userId == null) {
            userId = getDefaultUserId();
        }

        List<DestinationResponse> destinations = destinationService.getSavedDestinations(userId, label);

        return ResponseEntity.ok(Map.of("destinations", destinations));
    }

    /**
     * 새 목적지 저장 (POST /v1/destinations)
     */
    @PostMapping
    public ResponseEntity<DestinationResponse> createDestination(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @Valid @RequestBody CreateDestinationRequest request) {

        if (userId == null) {
            userId = getDefaultUserId();
        }

        DestinationResponse response = destinationService.createDestination(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 목적지 상세 조회 (GET /v1/destinations/{destinationId})
     */
    @GetMapping("/{destinationId}")
    public ResponseEntity<DestinationResponse> getDestination(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID destinationId) {

        if (userId == null) {
            userId = getDefaultUserId();
        }

        List<DestinationResponse> destinations = destinationService.getSavedDestinations(userId, null);
        return destinations.stream()
                .filter(d -> d.getId().equals(destinationId))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 목적지 수정 (PATCH /v1/destinations/{destinationId})
     */
    @PatchMapping("/{destinationId}")
    public ResponseEntity<DestinationResponse> updateDestination(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID destinationId,
            @Valid @RequestBody UpdateDestinationRequest request) {

        if (userId == null) {
            userId = getDefaultUserId();
        }

        DestinationResponse response = destinationService.updateDestination(userId, destinationId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 목적지 삭제 (DELETE /v1/destinations/{destinationId})
     */
    @DeleteMapping("/{destinationId}")
    public ResponseEntity<Void> deleteDestination(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID destinationId) {

        if (userId == null) {
            userId = getDefaultUserId();
        }

        destinationService.deleteDestination(userId, destinationId);
        return ResponseEntity.noContent().build();
    }

    private static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private UUID getDefaultUserId() {
        return DEFAULT_USER_ID;
    }
}
