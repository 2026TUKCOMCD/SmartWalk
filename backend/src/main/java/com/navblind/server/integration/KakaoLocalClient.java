package com.navblind.server.integration;

import com.navblind.server.config.KakaoProperties;
import com.navblind.server.dto.DestinationDto.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class KakaoLocalClient {

    private final WebClient.Builder webClientBuilder;
    private final KakaoProperties kakaoProperties;

    private static final String KEYWORD_URL = "https://dapi.kakao.com/v2/local/search/keyword.json";
    private static final String ADDRESS_URL = "https://dapi.kakao.com/v2/local/search/address.json";
    private static final String REVERSE_URL  = "https://dapi.kakao.com/v2/local/geo/coord2address.json";
    private static final int MAX_PAGE_SIZE = 15;
    // 같은 장소로 간주할 좌표 허용 오차 (약 50m)
    private static final double DEDUP_THRESHOLD = 0.0005;

    /**
     * 키워드 검색 + 주소 검색을 병행하여 결과를 합칩니다.
     */
    @SuppressWarnings("unchecked")
    public List<SearchResult> search(String query, Double lat, Double lng, int limit) {
        List<SearchResult> results = new ArrayList<>();

        // 1. 키워드 검색
        results.addAll(fetchKeyword(query, lat, lng, limit));

        // 2. 주소 검색 (키워드로 못 찾은 장소 보완)
        results.addAll(fetchAddress(query, lat, lng, limit));

        // 3. 중복 제거 (좌표 기준)
        List<SearchResult> deduped = deduplicate(results);

        // 4. 거리순 정렬 (위치 제공 시), 없으면 원래 순서 유지
        if (lat != null && lng != null) {
            deduped.sort((a, b) -> {
                int da = a.getDistance() != null ? a.getDistance() : Integer.MAX_VALUE;
                int db = b.getDistance() != null ? b.getDistance() : Integer.MAX_VALUE;
                return Integer.compare(da, db);
            });
        }

        List<SearchResult> final_ = deduped.stream().limit(limit).toList();
        log.debug("Kakao search '{}': 키워드+주소 합산 {}건 반환", query, final_.size());
        return final_;
    }

    private List<SearchResult> fetchKeyword(String query, Double lat, Double lng, int limit) {
        List<SearchResult> results = new ArrayList<>();
        int remaining = Math.min(limit, 30);
        int page = 1;

        while (remaining > 0) {
            int size = Math.min(remaining, MAX_PAGE_SIZE);
            Map<String, Object> response = fetchPage(KEYWORD_URL, query, lat, lng, size, page);
            if (response == null) break;

            List<Map<String, Object>> documents = (List<Map<String, Object>>) response.get("documents");
            if (documents == null || documents.isEmpty()) break;

            for (Map<String, Object> doc : documents) {
                SearchResult result = parseDocument(doc, lat, lng);
                if (result != null) results.add(result);
            }

            Map<String, Object> meta = (Map<String, Object>) response.get("meta");
            boolean isEnd = meta != null && Boolean.TRUE.equals(meta.get("is_end"));
            if (isEnd) break;

            remaining -= documents.size();
            page++;
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private List<SearchResult> fetchAddress(String query, Double lat, Double lng, int limit) {
        List<SearchResult> results = new ArrayList<>();
        try {
            StringBuilder urlBuilder = new StringBuilder(ADDRESS_URL)
                    .append("?query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8))
                    .append("&size=").append(Math.min(limit, MAX_PAGE_SIZE));

            WebClient webClient = webClientBuilder.build();
            Map<String, Object> response = webClient.get()
                    .uri(URI.create(urlBuilder.toString()))
                    .header("Authorization", "KakaoAK " + kakaoProperties.apiKey())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(kakaoProperties.timeout()))
                    .block();

            if (response == null) return results;

            List<Map<String, Object>> documents = (List<Map<String, Object>>) response.get("documents");
            if (documents == null) return results;

            for (Map<String, Object> doc : documents) {
                SearchResult result = parseAddressDocument(doc, lat, lng);
                if (result != null) results.add(result);
            }
        } catch (Exception e) {
            log.debug("Kakao 주소 검색 실패 (무시): {}", e.getMessage());
        }
        return results;
    }

    private List<SearchResult> deduplicate(List<SearchResult> results) {
        List<SearchResult> deduped = new ArrayList<>();
        for (SearchResult candidate : results) {
            boolean isDuplicate = deduped.stream().anyMatch(existing ->
                    Math.abs(existing.getLatitude() - candidate.getLatitude()) < DEDUP_THRESHOLD &&
                    Math.abs(existing.getLongitude() - candidate.getLongitude()) < DEDUP_THRESHOLD);
            if (!isDuplicate) deduped.add(candidate);
        }
        return deduped;
    }

    /**
     * 좌표를 주소로 변환합니다 (역지오코딩).
     * x=경도, y=위도
     */
    @SuppressWarnings("unchecked")
    public String reverseGeocode(double lat, double lng) {
        String url = REVERSE_URL + "?x=" + lng + "&y=" + lat;
        try {
            WebClient webClient = webClientBuilder.build();
            Map<String, Object> response = webClient.get()
                    .uri(URI.create(url))
                    .header("Authorization", "KakaoAK " + kakaoProperties.apiKey())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(kakaoProperties.timeout()))
                    .block();

            if (response == null) return null;

            List<Map<String, Object>> documents = (List<Map<String, Object>>) response.get("documents");
            if (documents == null || documents.isEmpty()) return null;

            Map<String, Object> first = documents.get(0);

            // 도로명 주소 우선
            Map<String, Object> roadAddress = (Map<String, Object>) first.get("road_address");
            if (roadAddress != null) {
                String building = (String) roadAddress.get("building_name");
                String addressName = (String) roadAddress.get("address_name");
                if (building != null && !building.isBlank()) return building;
                if (addressName != null && !addressName.isBlank()) return addressName;
            }

            // 지번 주소 fallback
            Map<String, Object> address = (Map<String, Object>) first.get("address");
            if (address != null) {
                String addressName = (String) address.get("address_name");
                if (addressName != null && !addressName.isBlank()) return addressName;
            }

            return null;
        } catch (Exception e) {
            log.error("Kakao 역지오코딩 실패: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> fetchPage(String baseUrl, String query, Double lat, Double lng, int size, int page) {
        try {
            StringBuilder urlBuilder = new StringBuilder(baseUrl)
                    .append("?query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8))
                    .append("&size=").append(size)
                    .append("&page=").append(page);

            // 현재 위치 기준 정렬 (거리순)
            if (lat != null && lng != null) {
                urlBuilder.append("&x=").append(lng)   // Kakao: x=경도
                          .append("&y=").append(lat)   // Kakao: y=위도
                          .append("&sort=distance");
            }

            WebClient webClient = webClientBuilder.build();
            return webClient.get()
                    .uri(URI.create(urlBuilder.toString()))
                    .header("Authorization", "KakaoAK " + kakaoProperties.apiKey())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(kakaoProperties.timeout()))
                    .block();
        } catch (Exception e) {
            log.error("Kakao 검색 실패 (page={}): {}", page, e.getMessage());
            return null;
        }
    }

    /** 주소 검색 응답 파싱 — address_name을 장소명으로 사용 */
    @SuppressWarnings("unchecked")
    private SearchResult parseAddressDocument(Map<String, Object> doc, Double userLat, Double userLng) {
        try {
            // 도로명 주소 우선
            Map<String, Object> roadAddr = (Map<String, Object>) doc.get("road_address");
            Map<String, Object> jibunAddr = (Map<String, Object>) doc.get("address");
            Map<String, Object> addr = roadAddr != null ? roadAddr : jibunAddr;
            if (addr == null) return null;

            String addressName = (String) addr.get("address_name");
            String x = (String) addr.get("x");
            String y = (String) addr.get("y");
            if (addressName == null || x == null || y == null) return null;

            double lng = Double.parseDouble(x);
            double lat = Double.parseDouble(y);

            Integer distance = null;
            if (userLat != null && userLng != null) {
                distance = (int) haversineMeters(userLat, userLng, lat, lng);
            }

            // 건물명이 있으면 이름으로 사용
            String buildingName = roadAddr != null ? (String) roadAddr.get("building_name") : null;
            String name = (buildingName != null && !buildingName.isBlank()) ? buildingName : addressName;

            return SearchResult.builder()
                    .name(name)
                    .latitude(lat)
                    .longitude(lng)
                    .address(addressName)
                    .distance(distance)
                    .category("주소")
                    .build();
        } catch (Exception e) {
            log.warn("Kakao 주소 결과 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    private SearchResult parseDocument(Map<String, Object> doc, Double userLat, Double userLng) {
        try {
            String placeName = (String) doc.get("place_name");
            String x = (String) doc.get("x"); // 경도
            String y = (String) doc.get("y"); // 위도
            if (placeName == null || x == null || y == null) return null;

            double lng = Double.parseDouble(x);
            double lat = Double.parseDouble(y);

            // 도로명 주소 우선, 없으면 지번 주소
            String roadAddress = (String) doc.get("road_address_name");
            String jibunAddress = (String) doc.get("address_name");
            String address = (roadAddress != null && !roadAddress.isBlank()) ? roadAddress : jibunAddress;

            // 카테고리 (대분류만)
            String categoryFull = (String) doc.get("category_name");
            String category = categoryFull != null && categoryFull.contains(" > ")
                    ? categoryFull.split(" > ")[0].trim()
                    : categoryFull;

            // 카카오가 거리 제공 시 사용, 없으면 직접 계산
            Integer distance = null;
            String distStr = (String) doc.get("distance");
            if (distStr != null && !distStr.isBlank()) {
                try { distance = Integer.parseInt(distStr); } catch (NumberFormatException ignored) {}
            }
            if (distance == null && userLat != null && userLng != null) {
                distance = (int) haversineMeters(userLat, userLng, lat, lng);
            }

            return SearchResult.builder()
                    .name(placeName)
                    .latitude(lat)
                    .longitude(lng)
                    .address(address)
                    .distance(distance)
                    .category(category)
                    .build();
        } catch (Exception e) {
            log.warn("Kakao 결과 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    private double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
