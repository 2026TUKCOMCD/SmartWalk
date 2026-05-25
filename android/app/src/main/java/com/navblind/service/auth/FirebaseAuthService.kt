package com.navblind.service.auth

import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase 인증 래퍼.
 *
 * build.gradle의 Firebase 의존성이 활성화되면 실제 FirebaseAuth를 사용한다.
 * 개발/테스트 단계에서는 getIdToken()이 빈 문자열을 반환해 백엔드의
 * firebase.disabled=true 모드(X-User-Id 헤더 인증)와 맞물려 동작한다.
 */
@Singleton
class FirebaseAuthService @Inject constructor() {

    /**
     * 현재 로그인된 사용자의 Firebase ID 토큰을 반환한다.
     * Firebase가 비활성화된 환경에서는 빈 문자열을 반환한다.
     */
    suspend fun getIdToken(): String {
        return runCatching {
            // Firebase 의존성 활성화 후 아래 코드 사용:
            // com.google.firebase.auth.FirebaseAuth.getInstance()
            //     .currentUser
            //     ?.getIdToken(false)
            //     ?.await()
            //     ?.token ?: ""
            ""
        }.getOrDefault("")
    }

    /**
     * 현재 로그인된 사용자의 UID를 반환한다.
     * Firebase가 비활성화된 환경에서는 개발용 UUID를 반환한다.
     */
    fun getCurrentUserId(): String {
        // Firebase 의존성 활성화 후:
        // return com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
        return "00000000-0000-0000-0000-000000000001"
    }

    fun isSignedIn(): Boolean {
        // Firebase 의존성 활성화 후:
        // return com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
        return true
    }

    suspend fun signOut() {
        // Firebase 의존성 활성화 후:
        // com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
    }
}
