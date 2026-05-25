package com.navblind.server.controller;

import com.navblind.server.dto.DeviceDto.*;
import com.navblind.server.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @PostMapping
    public ResponseEntity<DeviceResponse> register(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @Valid @RequestBody RegisterDeviceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(deviceService.registerDevice(resolveUser(userId), request));
    }

    @GetMapping
    public ResponseEntity<List<DeviceResponse>> list(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        return ResponseEntity.ok(deviceService.getDevices(resolveUser(userId)));
    }

    @GetMapping("/{deviceId}")
    public ResponseEntity<DeviceResponse> get(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID deviceId) {
        return ResponseEntity.ok(deviceService.getDevice(resolveUser(userId), deviceId));
    }

    @PatchMapping("/{deviceId}/connection")
    public ResponseEntity<DeviceResponse> updateConnection(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody UpdateConnectionRequest request) {
        return ResponseEntity.ok(deviceService.updateConnection(resolveUser(userId), deviceId, request));
    }

    @DeleteMapping("/{deviceId}/connection")
    public ResponseEntity<Void> disconnect(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID deviceId) {
        deviceService.disconnect(resolveUser(userId), deviceId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID deviceId) {
        deviceService.deleteDevice(resolveUser(userId), deviceId);
        return ResponseEntity.noContent().build();
    }

    private UUID resolveUser(UUID userId) {
        return userId != null ? userId : DEFAULT_USER_ID;
    }
}
