package com.navblind

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NavBlindApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize any application-wide components here
    }
}
