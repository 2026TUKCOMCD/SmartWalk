package com.navblind.data.repository

import com.navblind.data.local.dao.PreferenceDao
import com.navblind.data.local.entity.LocalPreference
import com.navblind.data.remote.AuthApi
import com.navblind.data.remote.PreferenceDto
import com.navblind.data.remote.UserApi
import com.navblind.data.remote.UserResponse
import com.navblind.domain.repository.UserRepository
import javax.inject.Inject

class UserRepositoryImpl @Inject constructor(
    private val userApi: UserApi,
    private val authApi: AuthApi,
    private val preferenceDao: PreferenceDao
) : UserRepository {

    override suspend fun getMe(): Result<UserResponse> = runCatching { userApi.getMe() }

    override suspend fun verifyAndSync(phoneNumber: String?): Result<UserResponse> =
        runCatching { authApi.verify(phoneNumber) }

    override suspend fun getPreferences(): Result<LocalPreference> = runCatching {
        // 로컬 우선, 없으면 서버에서 내려받아 캐시
        preferenceDao.get() ?: run {
            val remote = userApi.getPreferences()
            val local = remote.toLocal()
            preferenceDao.save(local)
            local
        }
    }

    override suspend fun updatePreferences(dto: PreferenceDto): Result<LocalPreference> =
        runCatching {
            val remote = userApi.updatePreferences(dto)
            val local = remote.toLocal()
            preferenceDao.save(local)
            local
        }

    private fun PreferenceDto.toLocal() = LocalPreference(
        speechRate = speechRate ?: 1.0f,
        speechPitch = speechPitch ?: 1.0f,
        alertDistanceMeters = alertDistanceMeters ?: 3.0f,
        vibrationEnabled = vibrationEnabled ?: true,
        avoidStairs = avoidStairs ?: false,
        avoidSteepSlopes = avoidSteepSlopes ?: false,
        highContrastMode = highContrastMode ?: false,
        language = language ?: "ko"
    )
}
