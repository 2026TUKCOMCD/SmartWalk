package com.navblind.server.repository;

import com.navblind.server.entity.SmartGlasses;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SmartGlassesRepository extends JpaRepository<SmartGlasses, UUID> {
    List<SmartGlasses> findByUserId(UUID userId);
    Optional<SmartGlasses> findByDeviceId(String deviceId);
    Optional<SmartGlasses> findByIdAndUserId(UUID id, UUID userId);
    boolean existsByDeviceId(String deviceId);
}
