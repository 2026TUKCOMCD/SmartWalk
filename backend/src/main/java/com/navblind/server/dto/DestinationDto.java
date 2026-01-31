package com.navblind.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;
import java.util.UUID;

//@Data: getter/setter/toString/equals/hashCode 등을 생성하는 lombok 어노테이션
//@NoArgsConstructor: 매개변수가 없는 기본 생성자를 만들어줌
//@AllArgsConstructor: 모든 필드를 매개변수로 받는 생성자를 만들어줌
//@Builder: 생성자를 통한 객체 생성과 다르게 필드 순서, 필요한 매개변수만 입력 등을 가능케 하는 빌더 코드를 자동으로 제작
public class DestinationDto {

    //Nominatim에서 검색한 결과(한 개)를 표현하는 DTO
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SearchResult {
        private String name;
        private Double latitude;
        private Double longitude;
        private String address;
        private Integer distance;
        private String category;
    }

    //검색 결과 전체를 담는 DTO
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SearchResponse {
        private List<SearchResult> results;
    }

    //사용자가 저장한 목적지(한 개)의 상세 정보를 담는 DTO
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DestinationResponse {
        private UUID id;
        private String name;
        private Double latitude;
        private Double longitude;
        private String address;
        private String label;
        private Integer useCount;
        private String createdAt;
    }

    //사용자가 저장한 목적지 전체를 담는 DTO
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DestinationsResponse {
        private List<DestinationResponse> destinations;
    }

    //새 목적지를 저장할 때 클라이언트가 보내는 요청 데이터 DTO
    //어노테이션을 통해 유효성 검사를 수행하여 잘못되면 400 Bad Request 반환
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateDestinationRequest {
        @NotBlank
        @Size(max = 200)
        private String name;

        @Min(-90) @Max(90)
        private Double latitude;

        @Min(-180) @Max(180)
        private Double longitude;

        @Size(max = 500)
        private String address;

        @Size(max = 50)
        private String label;
    }

    //저장했던 목적지 정보의 일부를 수정할 때 사용하는 DTO
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateDestinationRequest {
        @Size(max = 200)
        private String name;

        @Size(max = 50)
        private String label;
    }
}
