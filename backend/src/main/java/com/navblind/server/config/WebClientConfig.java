package com.navblind.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
WebClient 설정 클래스
Spring WebFlux 기반의 non-blocking HTTP 클라이언트를 제공
주요 목적:
- OSRM, Nominatim 등 외부 API를 비동기(non-blocking) 방식으로 호출
- 요청 처리 중 I/O 대기 시 스레드가 블록되지 않도록 함
    → 제한된 스레드 풀(Event Loop)로도 높은 동시성을 처리 가능

WebClient는 Reactor Netty 위에서 동작하며,
Mono/Flux 기반의 선언적 체인(map, flatMap 등)을 통해
비동기 작업 흐름을 자연스럽게 구성할 수 있음

이 설정을 통해 기본 WebClient.Builder 빈을 제공하며,
필요 시 타임아웃, 헤더, 재시도, 로깅 등 공통 설정을 추가할 수 있음
*/
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
