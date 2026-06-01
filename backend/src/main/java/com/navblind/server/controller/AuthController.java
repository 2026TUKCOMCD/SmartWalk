package com.navblind.server.controller;

import com.navblind.server.dto.UserDto.*;
import com.navblind.server.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserService userService;

    /**
     * Firebase 토큰 검증 후 사용자 생성/조회 (POST /v1/auth/verify)
     * FirebaseAuthFilter가 이미 토큰을 검증하고 Authentication을 설정한다.
     * disabled 모드에서는 X-User-Id 헤더가 uid로 들어온다.
     */
    @PostMapping("/verify")
    public ResponseEntity<UserResponse> verify(
            Authentication authentication,
            @RequestParam(required = false) String phoneNumber) {
        String uid = authentication.getName();
        log.info("Auth verify for uid: {}", uid);
        UserResponse response = userService.findOrCreate(uid, phoneNumber);
        return ResponseEntity.ok(response);
    }
}
