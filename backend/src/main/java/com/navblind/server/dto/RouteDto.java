package com.navblind.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;
import java.util.UUID;
//@Data: getter/setter/toString/equals/hashCode 등을 생성하는 lombok 어노테이션
//@NoArgsConstructor: 매개변수가 없는 기본 생성자를 만들어줌
//@AllArgsConstructor: 모든 필드를 매개변수로 받는 생성자를 만들어줌
//@Builder: 생성자를 통한 객체 생성과 다르게 필드 순서, 필요한 매개변수만 입력 등을 가능케 하는 빌더 코드를 자동으로 제작
public class RouteDto {

    //사용자가 출발지와 목적지를 입력했을 때 경로를 알기 위해 보내는 요청을 담는 DTO
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RouteRequest {
        @NotNull
        @Min(-90) @Max(90)
        private Double originLat;

        @NotNull
        @Min(-180) @Max(180)
        private Double originLng;

        @NotNull
        @Min(-90) @Max(90)
        private Double destLat;

        @NotNull
        @Min(-180) @Max(180)
        private Double destLng;

        private String destName;

        @Builder.Default
        private Boolean usePreferences = true;
    }

    //경로 이탈 등의 이유로 중간에 경로를 재탐색할 때 쓰는 요청을 담는 DTO
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RerouteRequest {
        @NotNull
        private UUID sessionId;

        @NotNull
        @Min(-90) @Max(90)
        private Double currentLat;

        @NotNull
        @Min(-180) @Max(180)
        private Double currentLng;
    }

    //OSRM에서 받은 경로 결과를 클라이언트에게 응답할 때 사용하는 DTO
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RouteResponse {
        private UUID sessionId;
        private Integer distance;
        private Integer duration;
        private List<Waypoint> waypoints;
        private List<Instruction> instructions;
    }

    //경로 상의 특정 지점(출발, 도착, 큰 교차로 등)을 표현하는 DTO
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Waypoint {
        private Double lat;
        private Double lng;
        private String name;
    }

    //실제 음성 안내에 들어갈 문장을 담는 DTO
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Instruction {
        private Integer step;
        private InstructionType type;
        private TurnModifier modifier;
        private String text;
        private Integer distance;
        private Waypoint location;
    }

    public enum InstructionType {
        depart,
        turn,
        arrive,
        continue_straight,
        crosswalk
    }

    public enum TurnModifier {
        left,
        right,
        straight,
        slight_left,
        slight_right,
        uturn
    }
}
