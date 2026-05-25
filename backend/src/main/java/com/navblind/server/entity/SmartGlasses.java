package com.navblind.server.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "smart_glasses", indexes = {
    @Index(name = "idx_glasses_user_id", columnList = "user_id"),
    @Index(name = "idx_glasses_device_id", columnList = "device_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmartGlasses {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_id", nullable = false, length = 64, unique = true)
    private String deviceId;

    @Column(name = "device_name", length = 100)
    @Builder.Default
    private String deviceName = "NavBlind Glasses";

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "stream_port")
    @Builder.Default
    private Integer streamPort = 80;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private GlassesStatus status = GlassesStatus.REGISTERED;

    @Column(name = "firmware_version", length = 32)
    private String firmwareVersion;

    @Column(name = "battery_level")
    private Integer batteryLevel;

    @Column(name = "last_connected")
    private LocalDateTime lastConnected;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void markConnected(String ipAddress) {
        this.ipAddress = ipAddress;
        this.status = GlassesStatus.CONNECTED;
        this.lastConnected = LocalDateTime.now();
    }

    public void markDisconnected() {
        this.status = GlassesStatus.DISCONNECTED;
    }

    public String streamUrl() {
        if (ipAddress == null) return null;
        return "http://" + ipAddress + ":" + streamPort + "/stream";
    }

    public enum GlassesStatus {
        REGISTERED, CONNECTED, DISCONNECTED
    }
}
