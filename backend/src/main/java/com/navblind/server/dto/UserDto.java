package com.navblind.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

public class UserDto {

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class VerifyRequest {
        @NotBlank
        private String idToken;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UserResponse {
        private UUID id;
        private String phoneNumber;
        private String displayName;
        private LocalDateTime createdAt;
        private LocalDateTime lastLogin;
        private PreferenceDto preference;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UpdateUserRequest {
        @Size(max = 100)
        private String displayName;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PreferenceDto {
        private Float speechRate;
        private Float speechPitch;
        private Float alertDistanceMeters;
        private Boolean vibrationEnabled;
        private Boolean avoidStairs;
        private Boolean avoidSteepSlopes;
        private Boolean highContrastMode;
        private String language;
    }
}
