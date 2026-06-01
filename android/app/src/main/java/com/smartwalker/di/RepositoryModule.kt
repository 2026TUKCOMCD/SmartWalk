package com.smartwalker.di

import com.smartwalker.data.repository.DeviceRepositoryImpl
import com.smartwalker.data.repository.NavigationRepositoryImpl
import com.smartwalker.data.repository.UserRepositoryImpl
import com.smartwalker.domain.repository.DeviceRepository
import com.smartwalker.domain.repository.NavigationRepository
import com.smartwalker.domain.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindNavigationRepository(impl: NavigationRepositoryImpl): NavigationRepository

    @Binds @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds @Singleton
    abstract fun bindDeviceRepository(impl: DeviceRepositoryImpl): DeviceRepository
}
