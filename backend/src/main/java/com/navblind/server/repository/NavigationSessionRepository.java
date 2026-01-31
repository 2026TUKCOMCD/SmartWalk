package com.navblind.server.repository;

import com.navblind.server.entity.NavigationSession;
import com.navblind.server.entity.NavigationSession.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

//Spring Data JPA에서 제공하는 규칙에 따라 메서드 이름을 작성하면, 이름을 해석하여 자동으로 쿼리 생성
//네비게이션 세션(한 번의 길 안내 기록)을 관리하는 기능 수행

@Repository
public interface NavigationSessionRepository extends JpaRepository<NavigationSession, UUID> {

    //세션ID가 해당 유저의 소유인지를 확인
    Optional<NavigationSession> findByIdAndUserId(UUID id, UUID userId);

    //사용자가 현재 진행 중인 네비게이션이 있는지 확인
    @Query("SELECT ns FROM NavigationSession ns WHERE ns.user.id = :userId AND ns.status = :status")
    Optional<NavigationSession> findActiveSession(@Param("userId") UUID userId, @Param("status") SessionStatus status);

    default Optional<NavigationSession> findActiveSession(UUID userId) {
        return findActiveSession(userId, SessionStatus.ACTIVE);
    }

    //사용자의 네비 이력을 최신순으로 가져옴
    Page<NavigationSession> findByUserIdOrderByStartedAtDesc(UUID userId, Pageable pageable);

    //사용자가 성공적으로 도착한 횟수를 나타냄
    @Query("SELECT COUNT(ns) FROM NavigationSession ns WHERE ns.user.id = :userId AND ns.status = 'COMPLETED'")
    long countCompletedByUserId(@Param("userId") UUID userId);
}
