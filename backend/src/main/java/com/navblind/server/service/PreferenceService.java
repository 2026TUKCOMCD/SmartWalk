package com.navblind.server.service;

import com.navblind.server.dto.UserDto.PreferenceDto;
import com.navblind.server.entity.Preference;
import com.navblind.server.entity.User;
import com.navblind.server.repository.PreferenceRepository;
import com.navblind.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PreferenceService {

    private final PreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

    @Cacheable(value = "preferences", key = "#userId")
    @Transactional(readOnly = true)
    public PreferenceDto getPreferences(UUID userId) {
        return preferenceRepository.findByUserId(userId)
                .map(this::toDto)
                .orElse(new PreferenceDto());
    }

    @CacheEvict(value = "preferences", key = "#userId")
    @Transactional
    public PreferenceDto updatePreferences(UUID userId, PreferenceDto dto) {
        Preference pref = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
                    return Preference.builder().user(user).build();
                });

        if (dto.getSpeechRate() != null) pref.setSpeechRate(dto.getSpeechRate());
        if (dto.getSpeechPitch() != null) pref.setSpeechPitch(dto.getSpeechPitch());
        if (dto.getAlertDistanceMeters() != null) pref.setAlertDistanceMeters(dto.getAlertDistanceMeters());
        if (dto.getVibrationEnabled() != null) pref.setVibrationEnabled(dto.getVibrationEnabled());
        if (dto.getAvoidStairs() != null) pref.setAvoidStairs(dto.getAvoidStairs());
        if (dto.getAvoidSteepSlopes() != null) pref.setAvoidSteepSlopes(dto.getAvoidSteepSlopes());
        if (dto.getHighContrastMode() != null) pref.setHighContrastMode(dto.getHighContrastMode());
        if (dto.getLanguage() != null) pref.setLanguage(dto.getLanguage());

        preferenceRepository.save(pref);
        log.info("Preferences updated for user {}", userId);
        return toDto(pref);
    }

    private PreferenceDto toDto(Preference p) {
        return PreferenceDto.builder()
                .speechRate(p.getSpeechRate())
                .speechPitch(p.getSpeechPitch())
                .alertDistanceMeters(p.getAlertDistanceMeters())
                .vibrationEnabled(p.getVibrationEnabled())
                .avoidStairs(p.getAvoidStairs())
                .avoidSteepSlopes(p.getAvoidSteepSlopes())
                .highContrastMode(p.getHighContrastMode())
                .language(p.getLanguage())
                .build();
    }
}
