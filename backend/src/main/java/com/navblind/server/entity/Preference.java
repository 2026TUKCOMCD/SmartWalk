package com.navblind.server.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "preferences", indexes = {
    @Index(name = "idx_pref_user_id", columnList = "user_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Preference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "speech_rate", nullable = false)
    @Builder.Default
    private Float speechRate = 1.0f;

    @Column(name = "speech_pitch", nullable = false)
    @Builder.Default
    private Float speechPitch = 1.0f;

    @Column(name = "alert_distance_meters", nullable = false)
    @Builder.Default
    private Float alertDistanceMeters = 3.0f;

    @Column(name = "vibration_enabled", nullable = false)
    @Builder.Default
    private Boolean vibrationEnabled = true;

    @Column(name = "avoid_stairs", nullable = false)
    @Builder.Default
    private Boolean avoidStairs = false;

    @Column(name = "avoid_steep_slopes", nullable = false)
    @Builder.Default
    private Boolean avoidSteepSlopes = false;

    @Column(name = "high_contrast_mode", nullable = false)
    @Builder.Default
    private Boolean highContrastMode = false;

    @Column(name = "language", length = 10)
    @Builder.Default
    private String language = "ko";

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
