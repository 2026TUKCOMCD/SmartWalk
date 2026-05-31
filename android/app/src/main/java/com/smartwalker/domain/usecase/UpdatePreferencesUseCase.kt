package com.smartwalker.domain.usecase

import com.smartwalker.data.local.entity.LocalPreference
import com.smartwalker.data.remote.PreferenceDto
import com.smartwalker.domain.repository.UserRepository
import javax.inject.Inject

class UpdatePreferencesUseCase @Inject constructor(
    private val userRepository: UserRepository
) {
    suspend operator fun invoke(dto: PreferenceDto): Result<LocalPreference> =
        userRepository.updatePreferences(dto)
}
