package com.navblind.service.location

import android.content.Context
import android.util.Log
import com.google.ar.core.*
import com.navblind.domain.model.Coordinate
import com.navblind.domain.model.FusedPosition
import com.navblind.domain.model.PositionSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ARCore Geospatial API를 사용하여 고정밀 위치 정보를 제공합니다.
 * Google Visual Positioning System (VPS)을 활용하여 GPS보다 정확한 위치를 계산합니다.
 */
@Singleton
class GeospatialService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var session: Session? = null
    private var earth: Earth? = null

    private val _geospatialPosition = MutableStateFlow<FusedPosition?>(null)
    val geospatialPosition: StateFlow<FusedPosition?> = _geospatialPosition.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    fun checkAvailability(): Boolean {
        return try {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            val isSupported = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED ||
                    availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ||
                    availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
            Log.d(TAG, "ARCore availability: $availability, supported: $isSupported")
            isSupported
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ARCore availability", e)
            false
        }
    }

    /**
     * ARCore Geospatial 세션을 초기화합니다.
     * 카메라 권한이 필요합니다.
     */
    fun initialize(): Boolean {
        return try {
            // ARCore가 설치되어 있고 지원되는지 확인
            if (!checkAvailability()) {
                Log.w(TAG, "ARCore is not available")
                _isAvailable.value = false
                return false
            }

            // ARCore 세션 생성
            session = Session(context).apply {
                // Geospatial API 활성화
                val config = config
                config.geospatialMode = Config.GeospatialMode.ENABLED
                configure(config)
            }

            earth = session?.earth
            _isAvailable.value = earth != null

            Log.d(TAG, "Geospatial service initialized, earth: ${earth != null}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Geospatial service", e)
            _isAvailable.value = false
            false
        }
    }

    /**
     * 현재 프레임에서 Geospatial 위치를 업데이트합니다.
     * ARCore 세션이 활성화되어 있을 때 호출해야 합니다.
     */
    fun updatePosition() {
        val currentEarth = earth ?: return

        try {
            val trackingState = currentEarth.trackingState
            if (trackingState != TrackingState.TRACKING) {
                Log.d(TAG, "Earth not tracking: $trackingState")
                return
            }

            val cameraGeospatialPose = currentEarth.cameraGeospatialPose

            // 위치 품질 확인
            val horizontalAccuracy = cameraGeospatialPose.horizontalAccuracy
            val headingAccuracy = cameraGeospatialPose.headingAccuracy

            // 정확도가 충분히 높은 경우에만 사용
            if (horizontalAccuracy <= MAX_HORIZONTAL_ACCURACY) {
                val position = FusedPosition(
                    coordinate = Coordinate(
                        latitude = cameraGeospatialPose.latitude,
                        longitude = cameraGeospatialPose.longitude
                    ),
                    accuracy = horizontalAccuracy.toFloat(),
                    altitude = cameraGeospatialPose.altitude,
                    heading = if (headingAccuracy <= MAX_HEADING_ACCURACY) {
                        cameraGeospatialPose.heading.toFloat()
                    } else null,
                    source = PositionSource.ARCORE_GEOSPATIAL
                )

                _geospatialPosition.value = position
                Log.d(TAG, "Geospatial position updated: ${position.coordinate}, " +
                        "accuracy: ${horizontalAccuracy}m")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating geospatial position", e)
        }
    }

    /**
     * 리소스를 해제합니다.
     */
    fun release() {
        try {
            session?.close()
            session = null
            earth = null
            _isAvailable.value = false
            Log.d(TAG, "Geospatial service released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Geospatial service", e)
        }
    }

    companion object {
        private const val TAG = "GeospatialService"
        private const val MAX_HORIZONTAL_ACCURACY = 25.0 // meters
        private const val MAX_HEADING_ACCURACY = 45.0 // degrees
    }
}
