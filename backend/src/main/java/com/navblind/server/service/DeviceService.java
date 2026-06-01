package com.navblind.server.service;

import com.navblind.server.dto.DeviceDto.*;
import com.navblind.server.entity.SmartGlasses;
import com.navblind.server.entity.User;
import com.navblind.server.repository.SmartGlassesRepository;
import com.navblind.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private final SmartGlassesRepository glassesRepository;
    private final UserRepository userRepository;

    @Transactional
    public DeviceResponse registerDevice(UUID userId, RegisterDeviceRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        SmartGlasses glasses = glassesRepository.findByDeviceId(request.getDeviceId())
                .orElseGet(() -> SmartGlasses.builder().user(user).deviceId(request.getDeviceId()).build());

        glasses.setUser(user);
        glasses.setDeviceName(request.getDeviceName() != null ? request.getDeviceName() : "NavBlind Glasses");
        if (request.getIpAddress() != null) glasses.setIpAddress(request.getIpAddress());
        if (request.getStreamPort() != null) glasses.setStreamPort(request.getStreamPort());
        if (request.getFirmwareVersion() != null) glasses.setFirmwareVersion(request.getFirmwareVersion());

        glasses = glassesRepository.save(glasses);
        log.info("Device registered: {} for user {}", request.getDeviceId(), userId);
        return toResponse(glasses);
    }

    @Transactional
    public DeviceResponse updateConnection(UUID userId, UUID deviceId, UpdateConnectionRequest request) {
        SmartGlasses glasses = glassesRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Device not found: " + deviceId));

        glasses.markConnected(request.getIpAddress());
        if (request.getBatteryLevel() != null) glasses.setBatteryLevel(request.getBatteryLevel());
        glasses = glassesRepository.save(glasses);
        log.info("Device connected: {} ip={}", glasses.getDeviceId(), request.getIpAddress());
        return toResponse(glasses);
    }

    @Transactional
    public void disconnect(UUID userId, UUID deviceId) {
        SmartGlasses glasses = glassesRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Device not found: " + deviceId));
        glasses.markDisconnected();
        glassesRepository.save(glasses);
        log.info("Device disconnected: {}", glasses.getDeviceId());
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> getDevices(UUID userId) {
        return glassesRepository.findByUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceResponse getDevice(UUID userId, UUID deviceId) {
        return glassesRepository.findByIdAndUserId(deviceId, userId)
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalArgumentException("Device not found: " + deviceId));
    }

    @Transactional
    public void deleteDevice(UUID userId, UUID deviceId) {
        SmartGlasses glasses = glassesRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Device not found: " + deviceId));
        glassesRepository.delete(glasses);
        log.info("Device deleted: {}", glasses.getDeviceId());
    }

    private DeviceResponse toResponse(SmartGlasses g) {
        return DeviceResponse.builder()
                .id(g.getId())
                .deviceId(g.getDeviceId())
                .deviceName(g.getDeviceName())
                .ipAddress(g.getIpAddress())
                .streamPort(g.getStreamPort())
                .status(g.getStatus())
                .firmwareVersion(g.getFirmwareVersion())
                .batteryLevel(g.getBatteryLevel())
                .streamUrl(g.streamUrl())
                .lastConnected(g.getLastConnected())
                .createdAt(g.getCreatedAt())
                .build();
    }
}
