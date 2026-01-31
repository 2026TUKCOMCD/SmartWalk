package com.navblind.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.Map;

//@Data: getter/setter/toString/equals/hashCode 등을 생성하는 lombok 어노테이션
//@NoArgsConstructor: 매개변수가 없는 기본 생성자를 만들어줌
//@AllArgsConstructor: 모든 필드를 매개변수로 받는 생성자를 만들어줌
//@Builder: 생성자를 통한 객체 생성과 다르게 필드 순서, 필요한 매개변수만 입력 등을 가능케 하는 빌더 코드를 자동으로 제작
//@JsonInclude(JsonInclude.Include.NON_NULL): JSON으로 변환(직렬화)할 때 null인 필드를 아예 출력하지 않도록 설정함

//모든 API에서 발생하는 에러를 일관된 형태로 클라이언트에게 전달하도록 하는 DTO
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private String error;
    private String message;
    private Map<String, Object> details;

    public static ErrorResponse of(String error, String message) {
        return ErrorResponse.builder()
                .error(error)
                .message(message)
                .build();
    }

    public static ErrorResponse of(String error, String message, Map<String, Object> details) {
        return ErrorResponse.builder()
                .error(error)
                .message(message)
                .details(details)
                .build();
    }
}
