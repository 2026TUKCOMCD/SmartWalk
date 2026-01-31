package com.navblind.server.repository;

import com.navblind.server.entity.Destination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

//Spring Data JPA에서 제공하는 규칙에 따라 메서드 이름을 작성하면, 이름을 해석하여 자동으로 쿼리 생성
//사용자의 즐겨찾기 장소(Destination)를 DB에서 꺼내오거나 저장/삭제하는 기능 수행

@Repository
public interface DestinationRepository extends JpaRepository<Destination, UUID> {

    //목적지 조회 횟수를 내림차순으로 하여 가져오기
    List<Destination> findByUserIdOrderByUseCountDesc(UUID userId);

    //해당 사용자의 특정 라벨이 붙은 목적지만 가져오기
    List<Destination> findByUserIdAndLabelOrderByUseCountDesc(UUID userId, String label);

    //ID의 목적지가 사용자 소유의 목적지인지 확인(다른 사람의 즐겨찾기를 못 보게 하기 위한 것)
    Optional<Destination> findByIdAndUserId(UUID id, UUID userId);

    //JPA 명명 규칙으로 담을 수 없어서 SQL 쿼리 작성
    //사용자가 가장 많이 간 장소 상위 N개만 가져오기
    @Query("SELECT d FROM Destination d WHERE d.user.id = :userId ORDER BY d.useCount DESC LIMIT :limit")
    List<Destination> findTopByUserIdOrderByUseCountDesc(@Param("userId") UUID userId, @Param("limit") int limit);

    //사용자가 저장한 목적지 개수를 가져옴
    long countByUserId(UUID userId);

    //등록된 목적지를 삭제함
    void deleteByIdAndUserId(UUID id, UUID userId);
}
