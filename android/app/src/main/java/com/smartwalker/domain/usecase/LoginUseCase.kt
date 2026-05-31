package com.smartwalker.domain.usecase

import com.smartwalker.data.remote.UserResponse
import com.smartwalker.domain.repository.UserRepository
import com.smartwalker.service.auth.FirebaseAuthService
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
