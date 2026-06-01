package com.navblind.server.controller;

import com.navblind.server.dto.UserDto.*;
import com.navblind.server.service.PreferenceService;
import com.navblind.server.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final PreferenceService preferenceService;
    private static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        return ResponseEntity.ok(userService.getUser(resolveUser(userId)));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateMe(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.updateUser(resolveUser(userId), request));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        userService.deleteUser(resolveUser(userId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/preferences")
    public ResponseEntity<PreferenceDto> getPreferences(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        return ResponseEntity.ok(preferenceService.getPreferences(resolveUser(userId)));
    }

    @PutMapping("/me/preferences")
    public ResponseEntity<PreferenceDto> updatePreferences(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @RequestBody PreferenceDto dto) {
        return ResponseEntity.ok(preferenceService.updatePreferences(resolveUser(userId), dto));
    }

    private UUID resolveUser(UUID userId) {
        return userId != null ? userId : DEFAULT_USER_ID;
    }
}
