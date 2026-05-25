package com.navblind.data.remote

import retrofit2.http.POST
import retrofit2.http.Query

interface AuthApi {

    @POST("auth/verify")
    suspend fun verify(
        @Query("phoneNumber") phoneNumber: String? = null
    ): UserResponse
}
