package com.navblind.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

//외부 HTTP API 호출에 필요한 기본 URL과 타임아웃 값을 설정 파일에서 가져와 자바객체화
//Nominatim은 OSM 내에서 주소와 좌표를 상호변환하는 도구(self-hosted로 docker내에 탑재)
@ConfigurationProperties(prefix = "nominatim")
public record NominatimProperties(
    String baseUrl,
    int timeout
) {}
