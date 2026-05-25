package com.navblind.domain.usecase

import com.navblind.data.remote.UserResponse
import com.navblind.domain.repository.UserRepository
import com.navblind.service.auth.FirebaseAuthService
import javax.inject.Inject

class LoginUseCase @Inject constructor(
    private val userRepository: UserRepository,
    private val authService: FirebaseAuthService
) {
    suspend operator fun invoke(phoneNumber: String? = null): Result<UserResponse> {
        if (!authService.isSignedIn()) {
            return Result.failure(IllegalStateException("로그인이 필요합니다"))
        }
        return userRepository.verifyAndSync(phoneNumber)
    }
}
