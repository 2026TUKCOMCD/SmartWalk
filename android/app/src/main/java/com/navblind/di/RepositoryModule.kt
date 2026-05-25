package com.navblind.di

import com.navblind.data.repository.DeviceRepositoryImpl
import com.navblind.data.repository.NavigationRepositoryImpl
import com.navblind.data.repository.UserRepositoryImpl
import com.navblind.domain.repository.DeviceRepository
import com.navblind.domain.repository.NavigationRepository
import com.navblind.domain.repository.UserRepository
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
