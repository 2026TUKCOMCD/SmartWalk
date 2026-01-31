package com.navblind.server.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

//사용자의 경로 탐색 이력을 저장하여 장기적으로 사용자 경험을 향상시키기 위해 저장
//이력 조회 & 재사용, 통계 & 개인화, 재탐색&장애물 회피 지원, 사용자 피드백 & 서비스 개선 등

//@NoArgsConstructor: 매개변수가 없는 기본 생성자를 만들어줌
//@AllArgsConstructor: 모든 필드를 매개변수로 받는 생성자를 만들어줌
//@Builder: 생성자를 통한 객체 생성과 다르게 필드 순서, 필요한 매개변수만 입력 등을 가능케 하는 빌더 코드를 자동으로 제작
//@Entity: JPA 엔티티임을 나타내며, DB 테이블과 1:1로 매핑되어, 이 클래스의 객체를 통해 DB에 CRUD가 가능
//@Table(name=?, indexes={}): 테이블 이름을 지정하며, DB에 @Index()를 통한 인덱스를 자동 생성시켜줌 
@Entity
@Table(name = "navigation_sessions", indexes = {
    @Index(name = "idx_session_user_active", columnList = "user_id, status"),  //사용자의 진행중인 경로 세션 인덱스
    @Index(name = "idx_session_user_history", columnList = "user_id, started_at DESC") //사용자의 최근 네비 이력 순으로 정렬 인덱스
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NavigationSession {

    //각 세션 고유 ID
    //@id: 해당 필드가 기본 키(PK)임을 나타냄
    //@Generatedvalue(strategy = GenerationType.UUID): UUID 방식으로 값 자동 생성
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    //@ManyToOne(fetch = FetchType.LAZY): 다대 일 관계를 나타내며, 이 객체를 필요할 때만 가져옴(지연 로딩)
    //@JoinColumn(name = "user_id", nullable = false): 외래 키 설정, 컬럼 이름이 user_id, null 불가
    //한 사용자는 여러 세션을 가짐
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    //출발지의 위도(좌표 정보의 일부를 나타냄)
    @Column(name = "origin_lat", nullable = false)
    private Double originLat;

    //출발지의 경도(좌표 정보의 일부를 나타냄)
    @Column(name = "origin_lng", nullable = false)
    private Double originLng;

    //도착지의 위도(좌표 정보의 일부를 나타냄)
    @Column(name = "dest_lat", nullable = false)
    private Double destLat;

    //도착지의 경도(좌표 정보의 일부를 나타냄)
    @Column(name = "dest_lng", nullable = false)
    private Double destLng;

    //도착지의 이름을 나타냄
    @Column(name = "dest_name", nullable = false, length = 200)
    private String destName;

    //@Enumerated(EnumType.STRING): enum(SessionStatus)을 열거형이 아닌 문자열로 저장
    //@Builder.Default: 기본값을 설정하여 빌더 코딩 시에도 입력되지 않은 부분이 기본값으로 들어갈 수 있음
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE;

    //언제 안내를 시작했는 지를 나타내며 자동으로 채워짐
    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    //언제 안내가 끝났는 지를 나타내며 자동으로 채워짐
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    //총 이동 거리를 나타냄
    @Column(name = "distance_meters")
    private Integer distanceMeters;

    //경로 재탐색 횟수를 나타냄
    //@Builder.Default: 기본값을 설정하여 빌더 코딩 시에도 입력되지 않은 부분이 기본값으로 들어갈 수 있음
    @Column(name = "reroute_count", nullable = false)
    @Builder.Default
    private Integer rerouteCount = 0;

    //경로 재탐색 시에 rerouteCount 1 증가
    public void incrementRerouteCount() {
        this.rerouteCount++;
    }

    //안내 완료 시에 호출하는 메서드
    public void complete() {
        this.status = SessionStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    //안내 취소 시에 호출하는 메서드
    public void cancel() {
        this.status = SessionStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    //안내 실패 시에 호출하는 메서드
    public void fail() {
        this.status = SessionStatus.FAILED;
        this.completedAt = LocalDateTime.now();
    }

    public enum SessionStatus {
        ACTIVE,
        COMPLETED,
        CANCELLED,
        FAILED
    }
}
