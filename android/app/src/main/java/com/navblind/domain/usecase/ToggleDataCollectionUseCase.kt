package com.navblind.domain.usecase

import android.content.Context
import com.navblind.BuildConfig
import com.navblind.service.recording.DataCollectionService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ToggleDataCollectionUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun start() = DataCollectionService.start(context, BuildConfig.GLASS_STREAM_URL)
    fun stop() = DataCollectionService.stop(context)
}
