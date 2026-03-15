package com.navblind.service.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * IMU 센서(가속도계, 자이로스코프, 지자기센서)를 활용하여
 * 방위각(heading)과 이동 정보를 제공하는 서비스입니다.
 *
 * VPS heading과 융합하여 더 안정적인 방위각을 제공합니다.
 */
@Singleton
class IMUSensorService @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // 센서 참조
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    // 센서 데이터 저장
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // 자이로스코프 적분용
    private var lastGyroTimestamp: Long = 0
    private var gyroHeading: Float = 0f

    // 상태
    private val _imuHeading = MutableStateFlow<IMUHeadingData?>(null)
    val imuHeading: StateFlow<IMUHeadingData?> = _imuHeading.asStateFlow()

    // 걸음 감지 (PDR용)
    private val _stepDetected = MutableSharedFlow<Unit>(extraBufferCapacity = 10)
    val stepDetected: SharedFlow<Unit> = _stepDetected.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // 이동 감지용
    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    private var accelerationHistory = mutableListOf<Float>()

    /**
     * IMU 센서 모니터링을 시작합니다.
     */
    fun startSensors() {
        if (_isRunning.value) return

        // Rotation Vector 센서가 있으면 사용 (더 정확함)
        if (rotationVector != null) {
            sensorManager.registerListener(
                this,
                rotationVector,
                SensorManager.SENSOR_DELAY_GAME
            )
            Log.d(TAG, "Using Rotation Vector sensor")
        } else {
            // 없으면 가속도계 + 지자기센서 사용
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            magnetometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            Log.d(TAG, "Using Accelerometer + Magnetometer")
        }

        // 자이로스코프 (회전 감지용)
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // 걸음 감지 (PDR용) — OS 레벨 보행 감지, 저전력
        stepDetector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        _isRunning.value = true
        Log.d(TAG, "IMU sensors started")
    }

    /**
     * IMU 센서 모니터링을 중지합니다.
     */
    fun stopSensors() {
        sensorManager.unregisterListener(this)
        _isRunning.value = false
        lastGyroTimestamp = 0
        accelerationHistory.clear()
        Log.d(TAG, "IMU sensors stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_MAGNETIC_FIELD -> handleMagnetometer(event)
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
            Sensor.TYPE_STEP_DETECTOR -> _stepDetected.tryEmit(Unit)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name} = $accuracy")
    }

    private fun handleRotationVector(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        // azimuth (Z축 기준 회전) = 방위각, 라디안 → 도
        val azimuthRad = orientationAngles[0]
        val azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()

        // 0-360 범위로 정규화
        val normalizedHeading = normalizeAngle(azimuthDeg)

        updateHeading(normalizedHeading, HeadingSource.ROTATION_VECTOR)
    }

    private fun handleAccelerometer(event: SensorEvent) {
        // 저역 필터 적용
        lowPassFilter(event.values, gravity)

        // 이동 감지
        detectMovement(event.values)

        // 지자기 센서와 함께 방위각 계산
        calculateHeadingFromAccelMag()
    }

    private fun handleMagnetometer(event: SensorEvent) {
        lowPassFilter(event.values, geomagnetic)
        calculateHeadingFromAccelMag()
    }

    private fun handleGyroscope(event: SensorEvent) {
        val timestamp = event.timestamp

        if (lastGyroTimestamp != 0L) {
            // 시간 차이 (나노초 → 초)
            val dt = (timestamp - lastGyroTimestamp) * NS_TO_S

            // Z축 회전 속도 (라디안/초)
            val omegaZ = event.values[2]

            // 적분하여 회전량 계산
            gyroHeading += Math.toDegrees((omegaZ * dt).toDouble()).toFloat()
            gyroHeading = normalizeAngle(gyroHeading)
        }

        lastGyroTimestamp = timestamp
    }

    private fun calculateHeadingFromAccelMag() {
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            gravity,
            geomagnetic
        )

        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthRad = orientationAngles[0]
            val azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
            val normalizedHeading = normalizeAngle(azimuthDeg)

            updateHeading(normalizedHeading, HeadingSource.ACCEL_MAG)
        }
    }

    private fun updateHeading(heading: Float, source: HeadingSource) {
        val currentData = _imuHeading.value
        val filteredHeading = if (currentData != null) {
            // 보완 필터: 이전 값과 새 값을 혼합
            complementaryFilter(currentData.heading, heading)
        } else {
            heading
        }

        _imuHeading.value = IMUHeadingData(
            heading = filteredHeading,
            rawHeading = heading,
            gyroHeading = gyroHeading,
            source = source,
            timestamp = System.currentTimeMillis(),
            isMoving = _isMoving.value
        )
    }

    private fun detectMovement(acceleration: FloatArray) {
        // 가속도 크기 계산
        val magnitude = kotlin.math.sqrt(
            acceleration[0] * acceleration[0] +
            acceleration[1] * acceleration[1] +
            acceleration[2] * acceleration[2]
        )

        // 중력 제거 후 가속도 크기
        val linearAccel = abs(magnitude - SensorManager.GRAVITY_EARTH)

        // 이동 평균에 추가
        accelerationHistory.add(linearAccel)
        if (accelerationHistory.size > ACCEL_HISTORY_SIZE) {
            accelerationHistory.removeAt(0)
        }

        // 평균 계산
        val avgAccel = accelerationHistory.average().toFloat()

        // 임계값 이상이면 이동 중으로 판단
        _isMoving.value = avgAccel > MOVEMENT_THRESHOLD
    }

    private fun lowPassFilter(input: FloatArray, output: FloatArray) {
        for (i in input.indices) {
            output[i] = output[i] + ALPHA * (input[i] - output[i])
        }
    }

    private fun complementaryFilter(oldHeading: Float, newHeading: Float): Float {
        // 각도 차이 계산 (wrap-around 고려)
        var delta = newHeading - oldHeading
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360

        // 보완 필터 적용
        val filtered = oldHeading + COMPLEMENTARY_ALPHA * delta
        return normalizeAngle(filtered)
    }

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360
        if (normalized < 0) normalized += 360
        return normalized
    }

    /**
     * IMU heading 업데이트를 Flow로 받습니다.
     */
    fun getHeadingUpdates(): Flow<IMUHeadingData> = callbackFlow {
        startSensors()

        val job = launch {
            imuHeading.collect { data ->
                data?.let { trySend(it) }
            }
        }

        awaitClose {
            job.cancel()
            stopSensors()
        }
    }

    companion object {
        private const val TAG = "IMUSensorService"
        private const val NS_TO_S = 1.0f / 1_000_000_000.0f
        private const val ALPHA = 0.1f // 저역 필터 계수
        private const val COMPLEMENTARY_ALPHA = 0.2f // 보완 필터 계수
        private const val MOVEMENT_THRESHOLD = 0.5f // m/s^2
        private const val ACCEL_HISTORY_SIZE = 10
    }
}

/**
 * IMU로 측정된 방위각 데이터
 */
data class IMUHeadingData(
    /** 필터링된 방위각 (0-360도, 북쪽 = 0) */
    val heading: Float,
    /** 원시 방위각 */
    val rawHeading: Float,
    /** 자이로스코프 적분 방위각 */
    val gyroHeading: Float,
    /** 측정 소스 */
    val source: HeadingSource,
    /** 타임스탬프 */
    val timestamp: Long,
    /** 사용자가 이동 중인지 여부 */
    val isMoving: Boolean
)

enum class HeadingSource {
    ROTATION_VECTOR,
    ACCEL_MAG,
    GYROSCOPE
}
