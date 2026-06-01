package com.navblind.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kakao")
public record KakaoProperties(
    String apiKey,
    int timeout
) {}
