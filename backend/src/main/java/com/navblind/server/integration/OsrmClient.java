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

//OSRM(경로 계산 엔진)과 통신하여 출발~도착 경로를 계산해주는 클래스

@Component
@RequiredArgsConstructor
@Slf4j
public class OsrmClient {

    private final WebClient.Builder webClientBuilder;
    private final OsrmProperties osrmProperties;

    //동기적으로 출발지부터 목적지까지의 거리를 계산해주는 함수, 압력: 출발/도착 위도 경도
    @SuppressWarnings("unchecked")
    public OsrmRouteResult getRoute(double originLat, double originLng, double destLat, double destLng) {
        String coordinates = String.format("%f,%f;%f,%f", originLng, originLat, destLng, destLat);
        //URL 조합기
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

    //비동기적으로 출발지부터 목적지까지의 거리를 계산해주는 함수, 압력: 출발/도착 위도 경도
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

    //OSRM에서 응답받은 원본 JSON을 DTO로 바꾸는 함수
    @SuppressWarnings("unchecked")
    private OsrmRouteResult parseOsrmResponse(Map<String, Object> response) {
        List<Map<String, Object>> routes = (List<Map<String, Object>>) response.get("routes");
        if (routes == null || routes.isEmpty()) {
            return null;
        }

        //경로를 가져와 총 거리와 시간을 추출
        Map<String, Object> route = routes.get(0);
        double distance = ((Number) route.get("distance")).doubleValue();
        double duration = ((Number) route.get("duration")).doubleValue();

        //legs는 구간을 나타내며 waypoint까지의 경로를 나타냄
        List<Map<String, Object>> legs = (List<Map<String, Object>>) route.get("legs");
        List<Instruction> instructions = new ArrayList<>();
        List<Waypoint> waypoints = new ArrayList<>();

        //steps는 지시사항을 뜻하며 하나의 구간(leg)안에서 사용자에게 말해줘야 할 구체적인 안내 지시들을 나타냄
        if (legs != null && !legs.isEmpty()) {
            Map<String, Object> leg = legs.get(0);
            List<Map<String, Object>> steps = (List<Map<String, Object>>) leg.get("steps");

            int stepNum = 0;
            for (Map<String, Object> step : steps) {
                //각 step에서 maneuver 정보 추출
                //maneuver는 각 step에서 사용자가 실제로 해야 할 행동의 세부 정보를 나타냄
                Map<String, Object> maneuver = (Map<String, Object>) step.get("maneuver");
                List<Double> location = (List<Double>) maneuver.get("location");

                //waypoint 생성(위치 + 이름)
                Waypoint waypoint = Waypoint.builder()
                        .lng(location.get(0))
                        .lat(location.get(1))
                        .name((String) step.get("name"))
                        .build();
                waypoints.add(waypoint);

                String maneuverType = (String) maneuver.get("type");
                String modifier = (String) maneuver.get("modifier");

                //음성 안내 텍스트 생성
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

        //최종 DTO 반환
        return OsrmRouteResult.builder()
                .distance((int) distance)
                .duration((int) duration)
                .waypoints(waypoints)
                .instructions(instructions)
                .build();
    }

    //OSRM에서 주는 maneuver.type을 정의한 InstructionType enum으로 매핑(영어->한국어)
    private InstructionType mapInstructionType(String osrmType) {
        if (osrmType == null) return InstructionType.continue_straight;
        return switch (osrmType) {
            case "depart"                               -> InstructionType.depart;
            case "arrive"                               -> InstructionType.arrive;
            // 명시적 회전 + 도로 구조상 방향 결정이 필요한 지점
            case "turn", "end of road", "fork"          -> InstructionType.turn;
            // 도로명 변경: 보행자에게는 중요한 분기점 신호
            case "new name"                             -> InstructionType.turn;
            // 횡단보도/신호등 (OSM에 highway=crossing 태그 있을 때 발생)
            case "notification"                         -> InstructionType.crosswalk;
            // 로터리: 진입 후 몇 번째 출구인지 modifier에 담김
            case "roundabout", "rotary",
                 "exit roundabout", "exit rotary"       -> InstructionType.turn;
            default                                     -> InstructionType.continue_straight;
        };
    }

    //turn일 때 방향(좌/우 등)을 정의한 enum으로 매핑(영어->한국어)
    private TurnModifier mapTurnModifier(String osrmModifier) {
        if (osrmModifier == null) return TurnModifier.straight;
        return switch (osrmModifier) {
            case "left"         -> TurnModifier.left;
            case "right"        -> TurnModifier.right;
            case "slight left"  -> TurnModifier.slight_left;
            case "slight right" -> TurnModifier.slight_right;
            case "uturn"        -> TurnModifier.uturn;
            case "straight"     -> TurnModifier.straight;
            // fork/end of road에서 나오는 sharp turn도 처리
            case "sharp left"   -> TurnModifier.left;
            case "sharp right"  -> TurnModifier.right;
            default             -> TurnModifier.straight;
        };
    }

    //OSRM의 영어 지시사항을 한국어 음성 안내 문장으로 변환
    private String generateKoreanInstruction(String type, String modifier, String streetName, int distance) {
        String distanceStr = formatDistance(distance);
        String street = (streetName != null && !streetName.isEmpty()) ? streetName : null;

        return switch (type) {
            case "depart" -> street != null
                    ? String.format("%s 방향으로 출발하세요", street)
                    : "경로를 따라 출발하세요";

            case "arrive" -> "목적지에 도착했습니다";

            // 명시적 회전
            case "turn" -> {
                String dir = modifierToKorean(modifier);
                yield street != null
                        ? String.format("%s 후 %s하여 %s 방향으로 가세요", distanceStr, dir, street)
                        : String.format("%s 후 %s하세요", distanceStr, dir);
            }

            // 도로 끝(T자 교차로): 반드시 좌/우 선택 필요
            case "end of road" -> {
                String dir = modifierToKorean(modifier);
                yield street != null
                        ? String.format("도로 끝에서 %s하여 %s 방향으로 가세요", dir, street)
                        : String.format("도로 끝에서 %s하세요", dir);
            }

            // 갈림길: 방향 안내 필요
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

            // 도로명 변경: 보행자에게 "여기서 길이 바뀝니다" 안내
            case "new name" -> street != null
                    ? String.format("%s 방향으로 계속 가세요", street)
                    : String.format("%s 직진하세요", distanceStr);

            // 횡단보도 / 신호등 (OSM highway=crossing 태그)
            case "notification" -> "횡단보도를 건너세요";

            // 로터리: 진입 후 modifier에 출구 번호가 담기는 경우도 있음
            case "roundabout", "rotary" -> {
                String dir = modifierToKorean(modifier);
                yield String.format("로터리에서 %s하세요", dir);
            }
            case "exit roundabout", "exit rotary" -> street != null
                    ? String.format("로터리를 나와 %s 방향으로 가세요", street)
                    : "로터리를 나오세요";

            // 그 외 (continue, merge 등): 도로명 있으면 활용
            default -> street != null
                    ? String.format("%s을(를) 따라 %s 직진하세요", street, distanceStr)
                    : String.format("%s 직진하세요", distanceStr);
        };
    }

    /** OSRM modifier 문자열을 한국어 동사로 변환합니다. */
    private String modifierToKorean(String modifier) {
        if (modifier == null) return "직진";
        return switch (modifier) {
            case "left"        -> "좌회전";
            case "right"       -> "우회전";
            case "slight left" -> "약간 왼쪽으로";
            case "slight right"-> "약간 오른쪽으로";
            case "uturn"       -> "유턴";
            case "straight"    -> "직진";
            default            -> "직진";
        };
    }

    //거리를 한국어로 보기 좋게 포맷팅
    private String formatDistance(int meters) {
        if (meters < 100) {
            return meters + "미터";
        } else if (meters < 1000) {
            return (meters / 10 * 10) + "미터";
        } else {
            return String.format("%.1f킬로미터", meters / 1000.0);
        }
    }

    /**
     * OSRM nearest API를 호출하여 주어진 좌표에서 가장 가까운 도로 위의 좌표를 반환합니다.
     * VPS/GPS 좌표를 OSM 도로망에 snap하는 데 사용됩니다.
     *
     * @param lat 위도
     * @param lng 경도
     * @param number 반환할 결과 수 (기본 1)
     * @return 가장 가까운 도로 좌표 정보
     */
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

    /**
     * 비동기 버전의 nearest API
     */
    @SuppressWarnings("unchecked")
    public Mono<NearestResult> getNearestRoadAsync(double lat, double lng, int number) {
        String url = String.format("%s/nearest/v1/foot/%f,%f?number=%d",
                osrmProperties.baseUrl(), lng, lat, number);

        log.debug("Requesting OSRM nearest async: {}", url);

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

    /**
     * OSRM nearest 응답을 파싱합니다.
     */
    @SuppressWarnings("unchecked")
    private NearestResult parseNearestResponse(Map<String, Object> response, double originalLat, double originalLng) {
        List<Map<String, Object>> waypoints = (List<Map<String, Object>>) response.get("waypoints");
        if (waypoints == null || waypoints.isEmpty()) {
            return null;
        }

        Map<String, Object> nearest = waypoints.get(0);
        List<Double> location = (List<Double>) nearest.get("location");
        double distance = ((Number) nearest.get("distance")).doubleValue();
        String name = (String) nearest.get("name");

        double snappedLng = location.get(0);
        double snappedLat = location.get(1);

        return NearestResult.builder()
                .originalLat(originalLat)
                .originalLng(originalLng)
                .snappedLat(snappedLat)
                .snappedLng(snappedLng)
                .distance(distance)
                .roadName(name != null && !name.isEmpty() ? name : null)
                .build();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OsrmRouteResult {
        private Integer distance;
        private Integer duration;
        private List<Waypoint> waypoints;
        private List<Instruction> instructions;
    }

    /**
     * OSRM nearest API 결과
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class NearestResult {
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
    }
}
