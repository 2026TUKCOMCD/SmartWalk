package com.smartwalker.di

import android.content.Context
import androidx.room.Room
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.smartwalker.BuildConfig
import com.smartwalker.data.local.AppDatabase
import com.smartwalker.data.local.dao.DestinationDao
import com.smartwalker.data.local.dao.PreferenceDao
import com.smartwalker.data.remote.AuthApi
import com.smartwalker.data.remote.DestinationApi
import com.smartwalker.data.remote.DeviceApi
import com.smartwalker.data.remote.NavigationApi
import com.smartwalker.data.remote.RoadSnapApi
import com.smartwalker.data.remote.UserApi
import com.smartwalker.service.streaming.CameraFrameSource
import com.smartwalker.service.streaming.LocalCameraSource
import com.smartwalker.service.streaming.MjpegCameraSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    // Add default user ID for demo (in production, use real auth)
                    .addHeader("X-User-Id", "00000000-0000-0000-0000-000000000001")
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL + "/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideNavigationApi(retrofit: Retrofit): NavigationApi {
        return retrofit.create(NavigationApi::class.java)
    }

    @Provides
    @Singleton
    fun provideRoadSnapApi(api: NavigationApi): RoadSnapApi = RoadSnapApi { lat, lng -> api.getNearestRoad(lat, lng) }

    @Provides
    @Singleton
    fun provideDestinationApi(retrofit: Retrofit): DestinationApi {
        return retrofit.create(DestinationApi::class.java)
    }

    @Provides @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi = retrofit.create(UserApi::class.java)

    @Provides @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides @Singleton
    fun provideDeviceApi(retrofit: Retrofit): DeviceApi = retrofit.create(DeviceApi::class.java)

    @Provides @Singleton
    fun provideDestinationDao(db: AppDatabase): DestinationDao = db.destinationDao()

    @Provides @Singleton
    fun providePreferenceDao(db: AppDatabase): PreferenceDao = db.preferenceDao()

    @Provides
    @Singleton
    fun provideFusedLocationProviderClient(
        @ApplicationContext context: Context
    ): FusedLocationProviderClient {
        return LocationServices.getFusedLocationProviderClient(context)
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "navblind.db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideCameraFrameSource(
        local: LocalCameraSource,
        mjpeg: MjpegCameraSource
    ): CameraFrameSource = if (BuildConfig.USE_LOCAL_CAMERA) local else mjpeg
}
