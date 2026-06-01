package com.smartwalker.service.location

import android.util.Log
import com.smartwalker.domain.model.Coordinate
import com.smartwalker.domain.model.FusedPosition
import com.smartwalker.domain.model.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 경로 이탈 감지 서비스
 * 사용자의 현재 위치와 경로 사이의 거리를 계산하여 이탈 여부를 판단합니다.
 *
 * RoadSnappingService를 활용하여 위치를 경로에 정합(snap)하고,
 * snap 불가 시 이탈로 판단합니다.
 */
@Singleton
class RouteDeviationDetector @Inject constructor(
    private val roadSnappingService: RoadSnappingService
) {

    private var currentRoute: Route? = null
    private var currentInstructionIndex = 0

    // Warning 상태 진입 시각 (0 = Warning 아님)
    private var warningStartTime: Long? = null

    private val _deviationState = MutableStateFlow<DeviationState>(DeviationState.OnRoute)
    val deviationState: StateFlow<DeviationState> = _deviationState.asStateFlow()

    private val _currentInstruction = MutableStateFlow(0)
    val currentInstruction: StateFlow<Int> = _currentInstruction.asStateFlow()

    /**
     * 새 경로를 설정합니다.
     */
    fun setRoute(route: Route) {
        currentRoute = route
        currentInstructionIndex = 0
        warningStartTime = null
        _deviationState.value = DeviationState.OnRoute
        _currentInstruction.value = 0
        roadSnappingService.setRoute(route)
        Log.d(TAG, "Route set with ${route.waypoints.size} waypoints")
    }

    fun clearRoute() {
        currentRoute = null
        currentInstructionIndex = 0
        warningStartTime = null
        _deviationState.value = DeviationState.OnRoute
        roadSnappingService.clearRoute()
    }

    // 마지막으로 snap된 위치
    private val _snappedPosition = MutableStateFlow<FusedPosition?>(null)
    val snappedPosition: StateFlow<FusedPosition?> = _snappedPosition.asStateFlow()

    // 경로 위에서의 남은 거리(미터). snap 성공 시 웨이포인트 기하로 계산.
    private val _remainingDistanceMeters = MutableStateFlow<Double?>(null)
    val remainingDistanceMeters: StateFlow<Double?> = _remainingDistanceMeters.asStateFlow()

    fun checkDeviation(position: FusedPosition) {
        val route = currentRoute ?: return

        // RoadSnappingService를 사용하여 위치를 경로에 snap
        val snapResult = roadSnappingService.snapToRoute(position)

        // snap 결과에 따라 처리
        val (newState, snappedPos) = when (snapResult) {
            is SnapResult.NoRoute -> {
                Pair(DeviationState.OnRoute, position)
            }
            is SnapResult.Snapped -> {
                Log.d(TAG, "Snapped to route: ${snapResult.distanceOffset}m offset, " +
                        "segment ${snapResult.segmentIndex}")
                warningStartTime = null
                // 웨이포인트 기하 기반 남은 거리 계산
                val remaining = computeRemainingDistance(
                    route, snapResult.segmentIndex, snapResult.snappedPosition.coordinate
                )
                _remainingDistanceMeters.value = remaining
                // 이동 거리 기반으로 현재 instruction 업데이트
                val traveled = route.distance - remaining
                updateCurrentInstruction(route, traveled)
                Pair(DeviationState.OnRoute, snapResult.snappedPosition)
            }
            is SnapResult.Deviated -> {
                Log.w(TAG, "Deviated from route: ${snapResult.distanceFromRoute}m")

                val state = when {
                    snapResult.distanceFromRoute > DEVIATION_THRESHOLD_CRITICAL -> {
                        warningStartTime = null
                        DeviationState.Deviated(snapResult.distanceFromRoute)
                    }
                    snapResult.distanceFromRoute > DEVIATION_THRESHOLD_WARNING -> {
                        val now = System.currentTimeMillis()
                        if (warningStartTime == null) {
                            warningStartTime = now
                            Log.d(TAG, "Warning 타이머 시작")
                            DeviationState.Warning(snapResult.distanceFromRoute)
                        } else if (now - warningStartTime!! >= WARNING_PERSIST_MS) {
                            Log.w(TAG, "Warning ${now - warningStartTime!!}ms 지속 → 재탐색 트리거")
                            warningStartTime = null
                            DeviationState.Deviated(snapResult.distanceFromRoute)
                        } else {
                            DeviationState.Warning(snapResult.distanceFromRoute)
                        }
                    }
                    else -> {
                        // snap 실패했지만 Warning 임계값 미만 → GPS 오차로 간주, OnRoute 유지
                        warningStartTime = null
                        DeviationState.OnRoute
                    }
                }
                Pair(state, position)
            }
        }

        _snappedPosition.value = snappedPos
        _deviationState.value = newState
    }

    /**
     * 현재 snap된 위치를 반환합니다.
     * snap이 불가능한 경우 원래 위치가 반환됩니다.
     */
    fun getSnappedPosition(position: FusedPosition): FusedPosition {
        return roadSnappingService.snapToRoute(position).effectivePosition
    }

    /**
     * 목적지에 도착했는지 확인합니다.
     */
    fun checkArrival(position: FusedPosition): Boolean {
        val route = currentRoute ?: return false

        if (route.waypoints.isEmpty()) return false

        val destination = route.waypoints.last()
        val distance = position.coordinate.distanceTo(
            Coordinate(destination.lat, destination.lng)
        )

        val arrived = distance < ARRIVAL_THRESHOLD
        if (arrived) {
            Log.d(TAG, "Arrived at destination (${distance}m)")
        }

        return arrived
    }

    /**
     * 누적 이동 거리를 기반으로 현재 instruction 인덱스를 업데이트합니다.
     *
     * instruction[i].distance = 해당 단계의 이동 거리이므로,
     * 누적 거리 합이 traveled를 넘기 전 마지막 단계가 현재 단계입니다.
     */
    private fun updateCurrentInstruction(route: Route, traveled: Double) {
        if (route.instructions.isEmpty()) return

        var cumulative = 0.0
        var targetIndex = currentInstructionIndex

        for (i in route.instructions.indices) {
            if (traveled >= cumulative && i > targetIndex) {
                targetIndex = i
            }
            cumulative += route.instructions[i].distance
        }

        if (targetIndex > currentInstructionIndex) {
            currentInstructionIndex = targetIndex
            _currentInstruction.value = targetIndex
            Log.d(TAG, "Advanced to instruction $targetIndex: ${route.instructions[targetIndex].text}")
        }
    }

    /**
     * 현재 snap 위치에서 경로 끝까지의 남은 거리를 웨이포인트 기하로 계산합니다 (미터).
     */
    private fun computeRemainingDistance(
        route: Route,
        segmentIndex: Int,
        snapPoint: Coordinate
    ): Double {
        val waypoints = route.waypoints
        if (segmentIndex >= waypoints.size - 1) return 0.0

        // 현재 세그먼트 끝점까지 거리
        val segEnd = waypoints[segmentIndex + 1].toCoordinate()
        var remaining = snapPoint.distanceTo(segEnd)

        // 이후 세그먼트들의 거리 합산
        for (i in (segmentIndex + 1) until (waypoints.size - 1)) {
            remaining += waypoints[i].toCoordinate().distanceTo(waypoints[i + 1].toCoordinate())
        }

        return remaining
    }

    sealed class DeviationState {
        data object OnRoute : DeviationState()
        data class Warning(val distanceMeters: Double) : DeviationState()
        data class Deviated(val distanceMeters: Double) : DeviationState()
    }

    companion object {
        private const val TAG = "RouteDeviationDetector"
        // 보행자 안전 기준으로 강화: 잘못된 방향으로 멀리 걷기 전에 재탐색.
        // GPS 순간 노이즈는 WARNING_PERSIST_MS(10초) 지속 조건이 걸러주므로 임계값을 낮춰도 안전하다.
        private const val DEVIATION_THRESHOLD_WARNING = 20.0   // meters (이전 30m → 20m)
        private const val DEVIATION_THRESHOLD_CRITICAL = 45.0  // meters (이전 60m → 45m, 즉시 재탐색)
        private const val ARRIVAL_THRESHOLD = 20.0 // meters (GPS로 더 줄이면 오도착 위험)

        // Warning 상태가 이 시간(ms) 이상 지속되면 재탐색 트리거
        private const val WARNING_PERSIST_MS = 10_000L
    }
}
