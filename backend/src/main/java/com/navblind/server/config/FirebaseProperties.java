package com.navblind.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

//application.yml에 있는 firebase: 아래의 속성들을 Java 객체로 매핑
//application.yml에 적힌 값들을 Java 객체에 매핑하여 가져온다
@ConfigurationProperties(prefix = "firebase")
public record FirebaseProperties(
    String credentialsPath,
    boolean disabled
) {}
