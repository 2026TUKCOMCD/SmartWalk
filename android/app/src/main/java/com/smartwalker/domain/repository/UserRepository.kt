package com.smartwalker.domain.repository

import com.smartwalker.data.local.entity.LocalPreference
import com.smartwalker.data.remote.PreferenceDto
import com.smartwalker.data.remote.UserResponse

interface UserRepository {
    suspend fun getMe(): Result<UserResponse>
    suspend fun verifyAndSync(phoneNumber: String? = null): Result<UserResponse>
    suspend fun getPreferences(): Result<LocalPreference>
    suspend fun updatePreferences(dto: PreferenceDto): Result<LocalPreference>
}
