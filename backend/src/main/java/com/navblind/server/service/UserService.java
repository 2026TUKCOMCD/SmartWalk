package com.navblind.server.service;

import com.navblind.server.dto.UserDto.*;
import com.navblind.server.entity.Preference;
import com.navblind.server.entity.User;
import com.navblind.server.repository.PreferenceRepository;
import com.navblind.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PreferenceRepository preferenceRepository;

    @Transactional
    public UserResponse findOrCreate(String firebaseUid, String phoneNumber) {
        User user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .firebaseUid(firebaseUid)
                            .phoneNumber(phoneNumber != null ? phoneNumber : firebaseUid)
                            .build();
                    newUser = userRepository.save(newUser);
                    createDefaultPreference(newUser);
                    log.info("New user created: {}", newUser.getId());
                    return newUser;
                });

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return toResponse(user);
    }

    @Transactional
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (request.getDisplayName() != null) user.setDisplayName(request.getDisplayName());
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public void deleteUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setIsActive(false);
        userRepository.save(user);
        log.info("User deactivated: {}", userId);
    }

    private void createDefaultPreference(User user) {
        preferenceRepository.save(Preference.builder().user(user).build());
    }

    private UserResponse toResponse(User user) {
        PreferenceDto prefDto = preferenceRepository.findByUserId(user.getId())
                .map(p -> PreferenceDto.builder()
                        .speechRate(p.getSpeechRate())
                        .speechPitch(p.getSpeechPitch())
                        .alertDistanceMeters(p.getAlertDistanceMeters())
                        .vibrationEnabled(p.getVibrationEnabled())
                        .avoidStairs(p.getAvoidStairs())
                        .avoidSteepSlopes(p.getAvoidSteepSlopes())
                        .highContrastMode(p.getHighContrastMode())
                        .language(p.getLanguage())
                        .build())
                .orElse(null);

        return UserResponse.builder()
                .id(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .displayName(user.getDisplayName())
                .createdAt(user.getCreatedAt())
                .lastLogin(user.getLastLogin())
                .preference(prefDto)
                .build();
    }
}
