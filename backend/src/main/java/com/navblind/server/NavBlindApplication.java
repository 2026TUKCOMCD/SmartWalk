package com.navblind.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NavBlindApplication {

    public static void main(String[] args) {
        SpringApplication.run(NavBlindApplication.class, args);
    }
}
