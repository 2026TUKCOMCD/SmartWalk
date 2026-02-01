package com.navblind.server.integration;

import com.navblind.server.config.NominatimProperties;
import com.navblind.server.dto.DestinationDto.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

//장소 검색 엔진(Nominatim)과 통신하여 "한국공학대"같은 키워드로 장소를 찾아주는 역할을 담당

@Component
@RequiredArgsConstructor
@Slf4j
public class NominatimClient {

    private final WebClient.Builder webClientBuilder;
    private final NominatimProperties nominatimProperties;

    //동기적으로 작동하는 검색 메서드, 입력으로 검색어(query), 현재 위치(위도, 경도), 결과 개수가 들어감
    @SuppressWarnings("unchecked")
    public List<SearchResult> search(String query, Double lat, Double lng, int limit) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

        //URL을 조합하여 장소를 검색하는 데 쓰이는 도구로, 한국만 검색하도록 고정
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(nominatimProperties.baseUrl())
                .append("/search?q=").append(encodedQuery)
                .append("&format=json")
                .append("&addressdetails=1")
                .append("&limit=").append(limit)
                .append("&countrycodes=kr"); // Limit to Korea

        // 위치가 있으면 viewbox로 주변 50km 정도 편향(bias)을 줌 (더 가까운 결과 우선)
        if (lat != null && lng != null) {
            double delta = 0.5; // approximately 50km
            urlBuilder.append("&viewbox=")
                    .append(lng - delta).append(",")
                    .append(lat + delta).append(",")
                    .append(lng + delta).append(",")
                    .append(lat - delta);
            urlBuilder.append("&bounded=0");
        }

        String url = urlBuilder.toString();
        log.debug("Nominatim search: {}", url);

        //WebClient으로 GET 요청을 보내 응답을 List<Map>으로 받아 parseNominatimResult()한 결과를 반환
        try {
            WebClient webClient = webClientBuilder.build();
            List<Map<String, Object>> response = webClient.get()
                    .uri(URI.create(url))
                    .header("User-Agent", "NavBlind/1.0")
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(Duration.ofMillis(nominatimProperties.timeout()))
                    .block();

            if (response == null || response.isEmpty()) {
                return List.of();
            }

            return parseNominatimResults(response, lat, lng);
        } catch (Exception e) {
            log.error("Error calling Nominatim: {}", e.getMessage());
            return List.of();
        }
    }

    //비동기적으로 작동하는 검색 메서드, 입력으로 검색어(query), 현재 위치(위도, 경도), 결과 개수가 들어감
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Mono<List<SearchResult>> searchAsync(String query, Double lat, Double lng, int limit) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(nominatimProperties.baseUrl())
                .append("/search?q=").append(encodedQuery)
                .append("&format=json")
                .append("&addressdetails=1")
                .append("&limit=").append(limit)
                .append("&countrycodes=kr");

        if (lat != null && lng != null) {
            double delta = 0.5;
            urlBuilder.append("&viewbox=")
                    .append(lng - delta).append(",")
                    .append(lat + delta).append(",")
                    .append(lng + delta).append(",")
                    .append(lat - delta);
            urlBuilder.append("&bounded=0");
        }

        String url = urlBuilder.toString();

        WebClient webClient = webClientBuilder.build();
        Mono<List> rawMono = webClient.get()
                .uri(URI.create(url))
                .header("User-Agent", "NavBlind/1.0")
                .retrieve()
                .bodyToMono(List.class)
                .timeout(Duration.ofMillis(nominatimProperties.timeout()));

        return rawMono
                .map(response -> parseNominatimResults((List<Map<String, Object>>) response, lat, lng))
                .onErrorResume(e -> {
                    log.error("Error calling Nominatim async: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    //Nominatim 원본 응답(JSON 배열)을 DTO로 변환하는 함수
    private List<SearchResult> parseNominatimResults(List<Map<String, Object>> results, Double userLat, Double userLng) {
        List<SearchResult> searchResults = new ArrayList<>();

        for (Map<String, Object> result : results) {
            try {
                double resultLat = Double.parseDouble((String) result.get("lat"));
                double resultLng = Double.parseDouble((String) result.get("lon"));

                String displayName = (String) result.get("display_name");
                String name = extractName(result);
                String category = (String) result.get("type");

                //유저의 위치가 주어지면 목적지까지의 거리를 계산
                Integer distance = null;
                if (userLat != null && userLng != null) {
                    distance = (int) calculateDistance(userLat, userLng, resultLat, resultLng);
                }

                searchResults.add(SearchResult.builder()
                        .name(name)
                        .latitude(resultLat)
                        .longitude(resultLng)
                        .address(displayName)
                        .distance(distance)
                        .category(translateCategory(category))
                        .build());
            } catch (Exception e) {
                log.warn("Error parsing Nominatim result: {}", e.getMessage());
            }
        }

        return searchResults;
    }

    //Nominatim 원본 응답(JSON 배열)에서 장소의 이름을 추출하는 함수
    @SuppressWarnings("unchecked")
    private String extractName(Map<String, Object> result) {
        // Try to get a meaningful name from address details
        Map<String, String> address = (Map<String, String>) result.get("address");
        if (address != null) {
            // Priority order for name extraction
            String[] keys = {"amenity", "tourism", "shop", "building", "road", "neighbourhood"};
            for (String key : keys) {
                if (address.containsKey(key) && address.get(key) != null) {
                    return address.get(key);
                }
            }
        }

        // Fall back to display_name, but try to extract first meaningful part
        String displayName = (String) result.get("display_name");
        if (displayName != null && displayName.contains(",")) {
            return displayName.split(",")[0].trim();
        }
        return displayName;
    }

    //Nominatim 원본 응답(JSON 배열)에서 category을 번역하는 함수
    private String translateCategory(String category) {
        if (category == null) return null;

        return switch (category) {
            case "amenity" -> "시설";
            case "tourism" -> "관광지";
            case "shop" -> "상점";
            case "building" -> "건물";
            case "road", "highway" -> "도로";
            case "station" -> "역";
            case "bus_stop" -> "버스정류장";
            case "restaurant" -> "음식점";
            case "cafe" -> "카페";
            case "hospital" -> "병원";
            case "pharmacy" -> "약국";
            case "school" -> "학교";
            case "university" -> "대학교";
            case "park" -> "공원";
            default -> category;
        };
    }

    //하버사인 공식을 사용하여 출발지부터 목적지까지의 거리를 계산
    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371000; // Earth's radius in meters

        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                        Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;
    }
}
