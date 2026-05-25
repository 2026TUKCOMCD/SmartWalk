package com.navblind.domain.usecase

import com.navblind.data.local.entity.LocalPreference
import com.navblind.data.remote.PreferenceDto
import com.navblind.domain.repository.UserRepository
import javax.inject.Inject

class UpdatePreferencesUseCase @Inject constructor(
    private val userRepository: UserRepository
) {
    suspend operator fun invoke(dto: PreferenceDto): Result<LocalPreference> =
        userRepository.updatePreferences(dto)
}
