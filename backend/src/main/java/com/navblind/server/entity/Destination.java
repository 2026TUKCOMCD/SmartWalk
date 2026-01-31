package com.navblind.server.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

//@NoArgsConstructor: 매개변수가 없는 기본 생성자를 만들어줌
//@AllArgsConstructor: 모든 필드를 매개변수로 받는 생성자를 만들어줌
//@Builder: 생성자를 통한 객체 생성과 다르게 필드 순서, 필요한 매개변수만 입력 등을 가능케 하는 빌더 코드를 자동으로 제작
//@Entity: JPA 엔티티임을 나타내며, DB 테이블과 1:1로 매핑되어, 이 클래스의 객체를 통해 DB에 CRUD가 가능
//@Table(name=?, indexes={}): 테이블 이름을 지정하며, DB에 @Index()를 통한 인덱스를 자동 생성시켜줌 
@Entity
@Table(name = "destinations", indexes = {
    @Index(name = "idx_destination_user_id", columnList = "user_id"),
    @Index(name = "idx_destination_use_count", columnList = "user_id, use_count DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Destination {

    //각 목적지를 고유하게 식별하는 ID
    //@id: 해당 필드가 기본 키(PK)임을 나타냄
    //@Generatedvalue(strategy = GenerationType.UUID): UUID 방식으로 값 자동 생성
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    //@ManyToOne(fetch = FetchType.LAZY): 다대 일 관계를 나타내며, 이 객체를 필요할 때만 가져옴(지연 로딩)
    //@JoinColumn(name = "user_id", nullable = false): 외래 키 설정, 컬럼 이름이 user_id, null 불가
    //이 목적지가 어느 사용자의 것인지를 연결
    //한 사용자가 여러 목적지를 가질 수 있음(1: N 관계)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    //장소의 이름을 나타냄, length는 최대 길이를 나타냄
    @Column(nullable = false, length = 200)
    private String name;

    //장소의 위도를 나타냄, precision은 소수점 포함 전체 자릿수를 나타냄
    @Column(nullable = false, precision = 10)
    private Double latitude;

    //장소의 경도를 나타냄
    @Column(nullable = false, precision = 11)
    private Double longitude;

    //장소의 주소를 나타냄(null 가능, Nominatim을 통해 구할 수 있음)
    @Column(length = 500)
    private String address;

    //사용자가 장소에 붙이는 태그
    @Column(length = 50)
    private String label;

    //이 목적지를 몇 번이나 네비게이션으로 갔는지 카운트
    @Column(name = "use_count", nullable = false)
    @Builder.Default
    private Integer useCount = 0;

    //생성된 일자이며, 자동으로 채워짐
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    //마지막으로 수정된 일자이며, 자동으로 채워짐
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    //네비게이션 도착 시 호출해서 useCount 1 증가(해당 장소에 자주 간다는 걸 DB에 반영)
    public void incrementUseCount() {
        this.useCount++;
    }
}
