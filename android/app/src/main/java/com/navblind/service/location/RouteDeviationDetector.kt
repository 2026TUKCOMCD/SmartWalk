package com.navblind.service.location

import android.util.Log
import com.navblind.domain.model.Coordinate
import com.navblind.domain.model.FusedPosition
import com.navblind.domain.model.Route
import com.navblind.domain.model.Waypoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 경로 이탈 감지 서비스
 * 사용자의 현재 위치와 경로 사이의 거리를 계산하여 이탈 여부를 판단합니다.
 */
@Singleton
class RouteDeviationDetector @Inject constructor() {

    private var currentRoute: Route? = null
    private var currentInstructionIndex = 0

    private val _deviationState = MutableStateFlow<DeviationState>(DeviationState.OnRoute)
    val deviationState: StateFlow<DeviationState> = _deviationState.asStateFlow()

    private val _currentInstruction = MutableStateFlow<Int>(0)
    val currentInstruction: StateFlow<Int> = _currentInstruction.asStateFlow()

    /**
     * 새 경로를 설정합니다.
     */
    fun setRoute(route: Route) {
        currentRoute = route
        currentInstructionIndex = 0
        _deviationState.value = DeviationState.OnRoute
        _currentInstruction.value = 0
        Log.d(TAG, "Route set with ${route.waypoints.size} waypoints")
    }

    /**
     * 경로를 제거합니다.
     */
    fun clearRoute() {
        currentRoute = null
        currentInstructionIndex = 0
        _deviationState.value = DeviationState.OnRoute
    }

    /**
     * 현재 위치를 기반으로 경로 이탈 여부를 확인합니다.
     */
    fun checkDeviation(position: FusedPosition): DeviationState {
        val route = currentRoute ?: return DeviationState.OnRoute

        val coordinate = position.coordinate

        // 경로상의 가장 가까운 지점까지의 거리 계산
        val (closestDistance, closestIndex) = findClosestPointOnRoute(coordinate, route.waypoints)

        Log.d(TAG, "Distance to route: ${closestDistance}m, closest waypoint: $closestIndex")

        // 현재 instruction 업데이트
        updateCurrentInstruction(coordinate, route)

        // 이탈 상태 결정
        val newState = when {
            closestDistance > DEVIATION_THRESHOLD_CRITICAL -> {
                Log.w(TAG, "Critical deviation detected: ${closestDistance}m")
                DeviationState.Deviated(closestDistance)
            }
            closestDistance > DEVIATION_THRESHOLD_WARNING -> {
                Log.d(TAG, "Warning: approaching deviation threshold: ${closestDistance}m")
                DeviationState.Warning(closestDistance)
            }
            else -> {
                DeviationState.OnRoute
            }
        }

        _deviationState.value = newState
        return newState
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

    private fun findClosestPointOnRoute(
        coordinate: Coordinate,
        waypoints: List<Waypoint>
    ): Pair<Double, Int> {
        if (waypoints.isEmpty()) return Pair(0.0, 0)

        var minDistance = Double.MAX_VALUE
        var closestIndex = 0

        // 모든 웨이포인트와의 거리 확인
        for ((index, waypoint) in waypoints.withIndex()) {
            val distance = coordinate.distanceTo(Coordinate(waypoint.lat, waypoint.lng))
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = index
            }
        }

        // 인접한 웨이포인트 사이의 선분까지의 거리도 확인
        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            val distanceToSegment = pointToSegmentDistance(
                coordinate,
                Coordinate(p1.lat, p1.lng),
                Coordinate(p2.lat, p2.lng)
            )
            if (distanceToSegment < minDistance) {
                minDistance = distanceToSegment
                closestIndex = i
            }
        }

        return Pair(minDistance, closestIndex)
    }

    private fun pointToSegmentDistance(
        point: Coordinate,
        segStart: Coordinate,
        segEnd: Coordinate
    ): Double {
        val dx = segEnd.longitude - segStart.longitude
        val dy = segEnd.latitude - segStart.latitude

        if (dx == 0.0 && dy == 0.0) {
            return point.distanceTo(segStart)
        }

        val t = maxOf(0.0, minOf(1.0,
            ((point.longitude - segStart.longitude) * dx + (point.latitude - segStart.latitude) * dy) /
                    (dx * dx + dy * dy)
        ))

        val projLat = segStart.latitude + t * dy
        val projLng = segStart.longitude + t * dx

        return point.distanceTo(Coordinate(projLat, projLng))
    }

    private fun updateCurrentInstruction(coordinate: Coordinate, route: Route) {
        if (route.instructions.isEmpty()) return

        // 현재 위치에서 각 instruction 위치까지의 거리 확인
        for (i in currentInstructionIndex until route.instructions.size) {
            val instruction = route.instructions[i]
            val instructionLocation = Coordinate(
                instruction.location.lat,
                instruction.location.lng
            )
            val distance = coordinate.distanceTo(instructionLocation)

            // instruction 위치에 도달하면 다음 instruction으로 진행
            if (distance < INSTRUCTION_REACHED_THRESHOLD) {
                if (i > currentInstructionIndex) {
                    currentInstructionIndex = i
                    _currentInstruction.value = i
                    Log.d(TAG, "Advanced to instruction $i: ${instruction.text}")
                }
            }
        }
    }

    sealed class DeviationState {
        object OnRoute : DeviationState()
        data class Warning(val distanceMeters: Double) : DeviationState()
        data class Deviated(val distanceMeters: Double) : DeviationState()
    }

    companion object {
        private const val TAG = "RouteDeviationDetector"
        private const val DEVIATION_THRESHOLD_WARNING = 10.0 // meters
        private const val DEVIATION_THRESHOLD_CRITICAL = 15.0 // meters
        private const val ARRIVAL_THRESHOLD = 20.0 // meters
        private const val INSTRUCTION_REACHED_THRESHOLD = 15.0 // meters
    }
}
