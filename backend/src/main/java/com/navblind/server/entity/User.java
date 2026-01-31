package com.navblind.server.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

//@NoArgsConstructor: 매개변수가 없는 기본 생성자를 만들어줌
//@AllArgsConstructor: 모든 필드를 매개변수로 받는 생성자를 만들어줌
//@Builder: 생성자를 통한 객체 생성과 다르게 필드 순서, 필요한 매개변수만 입력 등을 가능케 하는 빌더 코드를 자동으로 제작
//JPA 엔티티로, DB 테이블과 1:1로 매핑됨
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    //사용자 고유 ID, 모든 테이블에서 user_id로 참조됨
    //@id: 해당 필드가 기본 키(PK)임을 나타냄
    //@Generatedvalue(strategy = GenerationType.UUID): UUID 방식으로 값 자동 생성
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    //로그인 식별자
    //Firebase 폰 인증으로 로그인
    @Column(name = "phone_number", unique = true, nullable = false, length = 20)
    private String phoneNumber;

    //앱에 보이는 사용자의 이름
    @Column(name = "display_name", length = 100)
    private String displayName;

    //Firebase Authentication에서 받은 UID
    //토큰 검증 시 이 값으로 사용자 매핑
    @Column(name = "firebase_uid", unique = true, length = 128)
    private String firebaseUid;

    //가입 시간을 나타냄
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    //마지막 로그인 시간을 나타냄
    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    //계정 활성 여부를 나타냄(탈퇴 시 false(유예기간))
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
