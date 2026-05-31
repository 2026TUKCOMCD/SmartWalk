package com.navblind.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "osrm")
public record OsrmProperties(
    String baseUrl,
    int timeout
) {}
