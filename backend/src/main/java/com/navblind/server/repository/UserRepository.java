package com.navblind.server.repository;

import com.navblind.server.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

//Spring Data JPA에서 제공하는 규칙에 따라 메서드 이름을 작성하면, 이름을 해석하여 자동으로 쿼리 생성

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    //이 전화번호로 가입한 사용자가 있는 지 확인
    Optional<User> findByPhoneNumber(String phoneNumber);

    //Firebase에서 받은 UID로 사용자를 찾음
    //토큰 검증 후 실제 사용자 객체를 가져올 때 사용
    Optional<User> findByFirebaseUid(String firebaseUid);

    //해당 번호로 이미 가입했는 지를 확인
    boolean existsByPhoneNumber(String phoneNumber);

    //해당 UID로 이미 가입했는 지를 확인
    boolean existsByFirebaseUid(String firebaseUid);
}
