package com.navblind.service.location

import android.util.Log
import com.navblind.data.remote.NavigationApi
import com.navblind.domain.model.Coordinate
import com.navblind.domain.model.FusedPosition
import com.navblind.domain.model.Route
import com.navblind.domain.model.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * VPS 좌표를 OSM 도로망(현재 경로의 waypoint)에 정합(snap)하는 서비스입니다.
 *
 * GPS/VPS 위치가 도로에서 약간 벗어난 경우, 가장 가까운 유효한 도로 세그먼트에
 * 사용자 위치를 보정하여 더 정확한 네비게이션을 제공합니다.
 *
 * 주요 기능:
 * 1. 현재 위치를 가장 가까운 경로 세그먼트에 snap
 * 2. snap 거리가 임계값을 초과하면 원래 위치 유지 (이탈 상태)
 * 3. 현재 진행 중인 세그먼트 인덱스 추적
 */
@Singleton
class RoadSnappingService @Inject constructor(
    private val navigationApi: NavigationApi
) {

    private var currentRoute: Route? = null
    private var currentSegmentIndex: Int = 0

    /**
     * 현재 경로를 설정합니다.
     */
    fun setRoute(route: Route) {
        currentRoute = route
        currentSegmentIndex = 0
        Log.d(TAG, "Route set with ${route.waypoints.size} waypoints")
    }

    /**
     * 경로를 해제합니다.
     */
    fun clearRoute() {
        currentRoute = null
        currentSegmentIndex = 0
    }

    /**
     * 위치를 경로에 snap합니다.
     *
     * @param position 원래 위치
     * @return snap된 위치와 snap 결과 정보
     */
    fun snapToRoute(position: FusedPosition): SnapResult {
        val route = currentRoute ?: return SnapResult.NoRoute(position)

        if (route.waypoints.size < 2) {
            return SnapResult.NoRoute(position)
        }

        val coordinate = position.coordinate

        // 현재 세그먼트 근처에서 검색 시작 (효율성 향상)
        val searchStart = maxOf(0, currentSegmentIndex - SEGMENT_SEARCH_WINDOW)
        val searchEnd = minOf(route.waypoints.size - 1, currentSegmentIndex + SEGMENT_SEARCH_WINDOW)

        var bestSnapPoint: Coordinate? = null
        var bestDistance = Double.MAX_VALUE
        var bestSegmentIndex = currentSegmentIndex
        var bestProgressOnSegment = 0.0

        // 각 세그먼트에 대해 가장 가까운 점 찾기
        for (i in searchStart until searchEnd) {
            val segmentStart = route.waypoints[i].toCoordinate()
            val segmentEnd = route.waypoints[i + 1].toCoordinate()

            val (snapPoint, progress) = projectPointOnSegment(coordinate, segmentStart, segmentEnd)
            val distance = haversineDistance(coordinate, snapPoint)

            if (distance < bestDistance) {
                bestDistance = distance
                bestSnapPoint = snapPoint
                bestSegmentIndex = i
                bestProgressOnSegment = progress
            }
        }

        // 전체 경로에서 검색 (로컬 검색에서 못 찾은 경우)
        if (bestDistance > MAX_SNAP_DISTANCE) {
            for (i in 0 until route.waypoints.size - 1) {
                if (i in searchStart until searchEnd) continue // 이미 검색함

                val segmentStart = route.waypoints[i].toCoordinate()
                val segmentEnd = route.waypoints[i + 1].toCoordinate()

                val (snapPoint, progress) = projectPointOnSegment(coordinate, segmentStart, segmentEnd)
                val distance = haversineDistance(coordinate, snapPoint)

                if (distance < bestDistance) {
                    bestDistance = distance
                    bestSnapPoint = snapPoint
                    bestSegmentIndex = i
                    bestProgressOnSegment = progress
                }
            }
        }

        val snapPoint = bestSnapPoint ?: return SnapResult.NoRoute(position)

        // snap 거리가 너무 크면 이탈로 판단
        if (bestDistance > MAX_SNAP_DISTANCE) {
            Log.d(TAG, "Position too far from route: ${bestDistance}m")
            return SnapResult.Deviated(
                originalPosition = position,
                nearestPointOnRoute = snapPoint,
                distanceFromRoute = bestDistance
            )
        }

        // 세그먼트 인덱스 업데이트 (앞으로만 진행)
        if (bestSegmentIndex > currentSegmentIndex) {
            currentSegmentIndex = bestSegmentIndex
        } else if (bestSegmentIndex == currentSegmentIndex && bestProgressOnSegment > 0.9) {
            // 세그먼트 끝에 도달하면 다음으로
            if (currentSegmentIndex < route.waypoints.size - 2) {
                currentSegmentIndex++
            }
        }

        // snap된 위치로 새 FusedPosition 생성
        val snappedPosition = position.copy(
            coordinate = snapPoint
        )

        // 스냅 로그는 세그먼트가 바뀔 때만 출력 (핫 경로 로그 최소화)
        if (bestSegmentIndex != currentSegmentIndex) {
            Log.d(TAG, "Segment advanced: $currentSegmentIndex → $bestSegmentIndex (${bestDistance}m offset)")
        }

        return SnapResult.Snapped(
            originalPosition = position,
            snappedPosition = snappedPosition,
            distanceOffset = bestDistance,
            segmentIndex = currentSegmentIndex,
            progressOnSegment = bestProgressOnSegment
        )
    }

    /**
     * 점을 선분에 투영합니다.
     *
     * @return 투영된 점과 선분 위의 진행률 (0-1)
     */
    private fun projectPointOnSegment(
        point: Coordinate,
        segmentStart: Coordinate,
        segmentEnd: Coordinate
    ): Pair<Coordinate, Double> {
        // 선분을 벡터로 변환
        val dx = segmentEnd.longitude - segmentStart.longitude
        val dy = segmentEnd.latitude - segmentStart.latitude

        // 선분 길이의 제곱
        val lengthSquared = dx * dx + dy * dy

        if (lengthSquared < 1e-12) {
            // 선분이 점인 경우
            return Pair(segmentStart, 0.0)
        }

        // 점에서 선분 시작점까지의 벡터
        val px = point.longitude - segmentStart.longitude
        val py = point.latitude - segmentStart.latitude

        // 투영 계수 (0-1 범위로 클램핑)
        val t = ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)

        // 투영된 점 계산
        val projectedLng = segmentStart.longitude + t * dx
        val projectedLat = segmentStart.latitude + t * dy

        return Pair(Coordinate(projectedLat, projectedLng), t)
    }

    /**
     * Haversine 공식을 사용하여 두 좌표 사이의 거리를 계산합니다 (미터).
     */
    private fun haversineDistance(coord1: Coordinate, coord2: Coordinate): Double {
        val lat1Rad = Math.toRadians(coord1.latitude)
        val lat2Rad = Math.toRadians(coord2.latitude)
        val deltaLatRad = Math.toRadians(coord2.latitude - coord1.latitude)
        val deltaLngRad = Math.toRadians(coord2.longitude - coord1.longitude)

        val a = sin(deltaLatRad / 2).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLngRad / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * 현재 진행 중인 세그먼트 인덱스를 반환합니다.
     */
    fun getCurrentSegmentIndex(): Int = currentSegmentIndex

    /**
     * 경로의 총 세그먼트 수를 반환합니다.
     */
    fun getTotalSegments(): Int = (currentRoute?.waypoints?.size ?: 1) - 1

    /**
     * 현재 세그먼트의 시작과 끝 좌표를 반환합니다.
     */
    fun getCurrentSegment(): Pair<Coordinate, Coordinate>? {
        val route = currentRoute ?: return null
        if (currentSegmentIndex >= route.waypoints.size - 1) return null

        return Pair(
            route.waypoints[currentSegmentIndex].toCoordinate(),
            route.waypoints[currentSegmentIndex + 1].toCoordinate()
        )
    }

    /**
     * 서버의 OSRM nearest API를 사용하여 위치를 OSM 도로에 snap합니다.
     * 경로와 무관하게 가장 가까운 도로를 찾습니다.
     *
     * 사용 시나리오:
     * - 경로 이탈 후 복귀 시
     * - 네비게이션 없이 걸을 때 도로 위치 확인
     *
     * @param position 원래 위치
     * @return snap된 위치와 도로 정보, 실패 시 null
     */
    suspend fun snapToNearestRoad(position: FusedPosition): ServerSnapResult? {
        return withContext(Dispatchers.IO) {
            try {
                val response = navigationApi.getNearestRoad(
                    lat = position.coordinate.latitude,
                    lng = position.coordinate.longitude
                )

                val snappedCoordinate = Coordinate(
                    latitude = response.snappedLat,
                    longitude = response.snappedLng
                )

                val snappedPosition = position.copy(
                    coordinate = snappedCoordinate
                )

                Log.d(TAG, "Server snap: ${response.distance}m to ${response.roadName ?: "unnamed road"}")

                ServerSnapResult(
                    originalPosition = position,
                    snappedPosition = snappedPosition,
                    distance = response.distance,
                    roadName = response.roadName,
                    isOnRoad = response.isOnRoad
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to snap to nearest road", e)
                null
            }
        }
    }

    /**
     * 스마트 snap: 경로가 있으면 경로 기반, 없으면 서버 기반
     */
    suspend fun smartSnap(position: FusedPosition): FusedPosition {
        // 경로가 있으면 경로 기반 snap 시도
        val routeSnapResult = snapToRoute(position)

        return when (routeSnapResult) {
            is SnapResult.Snapped -> {
                // 경로 위에 있으면 경로 기반 snap 사용
                routeSnapResult.snappedPosition
            }
            is SnapResult.Deviated -> {
                // 경로에서 이탈했으면 서버 기반 snap 시도
                val serverResult = snapToNearestRoad(position)
                if (serverResult != null && serverResult.isOnRoad) {
                    serverResult.snappedPosition
                } else {
                    position // snap 실패 시 원래 위치
                }
            }
            is SnapResult.NoRoute -> {
                // 경로가 없으면 서버 기반 snap
                val serverResult = snapToNearestRoad(position)
                serverResult?.snappedPosition ?: position
            }
        }
    }

    companion object {
        private const val TAG = "RoadSnappingService"
        private const val EARTH_RADIUS_METERS = 6371000.0

        // snap 관련 설정
        private const val MAX_SNAP_DISTANCE = 30.0 // 미터, 이 거리 이상이면 이탈로 판단
        private const val SEGMENT_SEARCH_WINDOW = 5 // 현재 세그먼트 주변 검색 범위
    }
}

/**
 * 서버 기반 snap 결과
 */
data class ServerSnapResult(
    val originalPosition: FusedPosition,
    val snappedPosition: FusedPosition,
    val distance: Double,
    val roadName: String?,
    val isOnRoad: Boolean
)

/**
 * Road snapping 결과
 */
sealed class SnapResult {
    /**
     * 경로가 없음
     */
    data class NoRoute(
        val position: FusedPosition
    ) : SnapResult()

    /**
     * 성공적으로 snap됨
     */
    data class Snapped(
        val originalPosition: FusedPosition,
        val snappedPosition: FusedPosition,
        val distanceOffset: Double,
        val segmentIndex: Int,
        val progressOnSegment: Double
    ) : SnapResult()

    /**
     * 경로에서 이탈함
     */
    data class Deviated(
        val originalPosition: FusedPosition,
        val nearestPointOnRoute: Coordinate,
        val distanceFromRoute: Double
    ) : SnapResult()

    /**
     * snap된 위치를 반환합니다 (이탈 시 원래 위치).
     */
    val effectivePosition: FusedPosition
        get() = when (this) {
            is NoRoute -> position
            is Snapped -> snappedPosition
            is Deviated -> originalPosition
        }

    /**
     * 경로 위에 있는지 여부
     */
    val isOnRoute: Boolean
        get() = this is Snapped
}
