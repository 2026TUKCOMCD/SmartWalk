package com.navblind.server.dto;

import com.navblind.server.entity.SmartGlasses;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

public class DeviceDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RegisterDeviceRequest {
        @NotBlank
        @Size(max = 64)
        private String deviceId;

        @Size(max = 100)
        private String deviceName;

        @Size(max = 45)
        private String ipAddress;

        private Integer streamPort;

        @Size(max = 32)
        private String firmwareVersion;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateConnectionRequest {
        @NotBlank
        @Size(max = 45)
        private String ipAddress;

        private Integer batteryLevel;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeviceResponse {
        private UUID id;
        private String deviceId;
        private String deviceName;
        private String ipAddress;
        private Integer streamPort;
        private SmartGlasses.GlassesStatus status;
        private String firmwareVersion;
        private Integer batteryLevel;
        private String streamUrl;
        private LocalDateTime lastConnected;
        private LocalDateTime createdAt;
    }
}
