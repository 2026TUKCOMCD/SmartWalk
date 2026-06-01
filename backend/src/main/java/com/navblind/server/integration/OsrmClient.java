package com.navblind.server.integration;

import com.navblind.server.config.OsrmProperties;
import com.navblind.server.dto.RouteDto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OsrmClient {

    private final WebClient.Builder webClientBuilder;
    private final OsrmProperties osrmProperties;

    @SuppressWarnings("unchecked")
    public OsrmRouteResult getRoute(double originLat, double originLng, double destLat, double destLng) {
        String coordinates = String.format("%f,%f;%f,%f", originLng, originLat, destLng, destLat);
        String url = String.format("%s/route/v1/foot/%s?overview=full&steps=true&geometries=geojson",
                osrmProperties.baseUrl(), coordinates);

        log.debug("Requesting OSRM route: {}", url);

        try {
            WebClient webClient = webClientBuilder.build();
            Map<String, Object> response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(osrmProperties.timeout()))
                    .block();

            if (response == null || !"Ok".equals(response.get("code"))) {
                log.warn("OSRM returned non-OK response: {}", response);
                return null;
            }

            return parseOsrmResponse(response);
        } catch (Exception e) {
            log.error("Error calling OSRM: {}", e.getMessage());
            return null;
        }
    }

    public Mono<OsrmRouteResult> getRouteAsync(double originLat, double originLng, double destLat, double destLng) {
        String coordinates = String.format("%f,%f;%f,%f", originLng, originLat, destLng, destLat);
        String url = String.format("%s/route/v1/foot/%s?overview=full&steps=true&geometries=geojson",
                osrmProperties.baseUrl(), coordinates);

        log.debug("Requesting OSRM route async: {}", url);

        WebClient webClient = webClientBuilder.build();
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(osrmProperties.timeout()))
                .map(this::parseOsrmResponse)
                .onErrorResume(e -> {
                    log.error("Error calling OSRM async: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    @SuppressWarnings("unchecked")
    private OsrmRouteResult parseOsrmResponse(Map<String, Object> response) {
        List<Map<String, Object>> routes = (List<Map<String, Object>>) response.get("routes");
        if (routes == null || routes.isEmpty()) return null;

        Map<String, Object> route = routes.get(0);
        double distance = ((Number) route.get("distance")).doubleValue();
        double duration = ((Number) route.get("duration")).doubleValue();

        List<Map<String, Object>> legs = (List<Map<String, Object>>) route.get("legs");
        List<Instruction> instructions = new ArrayList<>();
        List<Waypoint> waypoints = new ArrayList<>();

        if (legs != null && !legs.isEmpty()) {
            Map<String, Object> leg = legs.get(0);
            List<Map<String, Object>> steps = (List<Map<String, Object>>) leg.get("steps");

            int stepNum = 0;
            for (Map<String, Object> step : steps) {
                Map<String, Object> maneuver = (Map<String, Object>) step.get("maneuver");
                List<Double> location = (List<Double>) maneuver.get("location");

                Waypoint waypoint = Waypoint.builder()
                        .lng(location.get(0))
                        .lat(location.get(1))
                        .name((String) step.get("name"))
                        .build();
                waypoints.add(waypoint);

                String maneuverType = (String) maneuver.get("type");
                String modifier = (String) maneuver.get("modifier");

                Instruction instruction = Instruction.builder()
                        .step(stepNum++)
                        .type(mapInstructionType(maneuverType))
                        .modifier(mapTurnModifier(modifier))
                        .text(generateKoreanInstruction(maneuverType, modifier, (String) step.get("name"),
                                ((Number) step.get("distance")).intValue()))
                        .distance(((Number) step.get("distance")).intValue())
                        .location(waypoint)
                        .build();
                instructions.add(instruction);
            }
        }

        return OsrmRouteResult.builder()
                .distance((int) distance)
                .duration((int) duration)
                .waypoints(waypoints)
                .instructions(instructions)
                .build();
    }

    private InstructionType mapInstructionType(String osrmType) {
        if (osrmType == null) return InstructionType.continue_straight;
        return switch (osrmType) {
            case "depart"                               -> InstructionType.depart;
            case "arrive"                               -> InstructionType.arrive;
            case "turn", "end of road", "fork"          -> InstructionType.turn;
            case "new name"                             -> InstructionType.turn;
            case "notification"                         -> InstructionType.crosswalk;
            case "roundabout", "rotary",
                 "exit roundabout", "exit rotary"       -> InstructionType.turn;
            default                                     -> InstructionType.continue_straight;
        };
    }

    private TurnModifier mapTurnModifier(String osrmModifier) {
        if (osrmModifier == null) return TurnModifier.straight;
        return switch (osrmModifier) {
            case "left"         -> TurnModifier.left;
            case "right"        -> TurnModifier.right;
            case "slight left"  -> TurnModifier.slight_left;
            case "slight right" -> TurnModifier.slight_right;
            case "uturn"        -> TurnModifier.uturn;
            case "straight"     -> TurnModifier.straight;
            case "sharp left"   -> TurnModifier.left;
            case "sharp right"  -> TurnModifier.right;
            default             -> TurnModifier.straight;
        };
    }

    private String generateKoreanInstruction(String type, String modifier, String streetName, int distance) {
        String distanceStr = formatDistance(distance);
        String street = (streetName != null && !streetName.isEmpty()) ? streetName : null;

        return switch (type) {
            case "depart" -> street != null
                    ? String.format("%s 방향으로 출발하세요", street)
                    : "경로를 따라 출발하세요";
            case "arrive" -> "목적지에 도착했습니다";
            case "turn" -> {
                String dir = modifierToKorean(modifier);
                yield street != null
                        ? String.format("%s 후 %s하여 %s 방향으로 가세요", distanceStr, dir, street)
                        : String.format("%s 후 %s하세요", distanceStr, dir);
            }
            case "end of road" -> {
                String dir = modifierToKorean(modifier);
                yield street != null
                        ? String.format("도로 끝에서 %s하여 %s 방향으로 가세요", dir, street)
                        : String.format("도로 끝에서 %s하세요", dir);
            }
            case "fork" -> {
                String dir = switch (modifier != null ? modifier : "") {
                    case "slight left", "left" -> "왼쪽 길";
                    case "slight right", "right" -> "오른쪽 길";
                    default -> "앞쪽 길";
                };
                yield street != null
                        ? String.format("갈림길에서 %s로 가세요. %s 방향입니다", dir, street)
                        : String.format("갈림길에서 %s로 가세요", dir);
            }
            case "new name" -> street != null
                    ? String.format("%s 방향으로 계속 가세요", street)
                    : String.format("%s 직진하세요", distanceStr);
            case "notification" -> "횡단보도를 건너세요";
            case "roundabout", "rotary" -> {
                String dir = modifierToKorean(modifier);
                yield String.format("로터리에서 %s하세요", dir);
            }
            case "exit roundabout", "exit rotary" -> street != null
                    ? String.format("로터리를 나와 %s 방향으로 가세요", street)
                    : "로터리를 나오세요";
            default -> street != null
                    ? String.format("%s을(를) 따라 %s 직진하세요", street, distanceStr)
                    : String.format("%s 직진하세요", distanceStr);
        };
    }

    private String modifierToKorean(String modifier) {
        if (modifier == null) return "직진";
        return switch (modifier) {
            case "left"         -> "좌회전";
            case "right"        -> "우회전";
            case "slight left"  -> "약간 왼쪽으로";
            case "slight right" -> "약간 오른쪽으로";
            case "uturn"        -> "유턴";
            case "straight"     -> "직진";
            default             -> "직진";
        };
    }

    private String formatDistance(int meters) {
        if (meters < 100) return meters + "미터";
        if (meters < 1000) return (meters / 10 * 10) + "미터";
        return String.format("%.1f킬로미터", meters / 1000.0);
    }

    @SuppressWarnings("unchecked")
    public NearestResult getNearestRoad(double lat, double lng, int number) {
        String url = String.format("%s/nearest/v1/foot/%f,%f?number=%d",
                osrmProperties.baseUrl(), lng, lat, number);

        log.debug("Requesting OSRM nearest: {}", url);

        try {
            WebClient webClient = webClientBuilder.build();
            Map<String, Object> response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(osrmProperties.timeout()))
                    .block();

            if (response == null || !"Ok".equals(response.get("code"))) {
                log.warn("OSRM nearest returned non-OK response: {}", response);
                return null;
            }

            return parseNearestResponse(response, lat, lng);
        } catch (Exception e) {
            log.error("Error calling OSRM nearest: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Mono<NearestResult> getNearestRoadAsync(double lat, double lng, int number) {
        String url = String.format("%s/nearest/v1/foot/%f,%f?number=%d",
                osrmProperties.baseUrl(), lng, lat, number);

        WebClient webClient = webClientBuilder.build();
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(osrmProperties.timeout()))
                .map(response -> parseNearestResponse(response, lat, lng))
                .onErrorResume(e -> {
                    log.error("Error calling OSRM nearest async: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    @SuppressWarnings("unchecked")
    private NearestResult parseNearestResponse(Map<String, Object> response, double originalLat, double originalLng) {
        List<Map<String, Object>> waypoints = (List<Map<String, Object>>) response.get("waypoints");
        if (waypoints == null || waypoints.isEmpty()) return null;

        Map<String, Object> nearest = waypoints.get(0);
        List<Double> location = (List<Double>) nearest.get("location");
        double distance = ((Number) nearest.get("distance")).doubleValue();
        String name = (String) nearest.get("name");

        return NearestResult.builder()
                .originalLat(originalLat)
                .originalLng(originalLng)
                .snappedLat(location.get(1))
                .snappedLng(location.get(0))
                .distance(distance)
                .roadName(name != null && !name.isEmpty() ? name : null)
                .build();
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class OsrmRouteResult {
        private Integer distance;
        private Integer duration;
        private List<Waypoint> waypoints;
        private List<Instruction> instructions;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class NearestResult {
        private Double originalLat;
        private Double originalLng;
        private Double snappedLat;
        private Double snappedLng;
        private Double distance;
        private String roadName;
    }
}
