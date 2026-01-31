package com.navblind.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

//외부 HTTP API 호출에 필요한 기본 URL과 타임아웃 값을 설정 파일에서 가져와 자바객체화
//OSRM은 OSM를 기반으로 하여 목적지까지의 경로 계산(self-hosted로 docker내에 탑재)
@ConfigurationProperties(prefix = "osrm")
public record OsrmProperties(
    String baseUrl,
    int timeout
) {}
