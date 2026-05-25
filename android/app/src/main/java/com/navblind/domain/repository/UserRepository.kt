package com.navblind.domain.repository

import com.navblind.data.local.entity.LocalPreference
import com.navblind.data.remote.PreferenceDto
import com.navblind.data.remote.UserResponse

interface UserRepository {
    suspend fun getMe(): Result<UserResponse>
    suspend fun verifyAndSync(phoneNumber: String? = null): Result<UserResponse>
    suspend fun getPreferences(): Result<LocalPreference>
    suspend fun updatePreferences(dto: PreferenceDto): Result<LocalPreference>
}
