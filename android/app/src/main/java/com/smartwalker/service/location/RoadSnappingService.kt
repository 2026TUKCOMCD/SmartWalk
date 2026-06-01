package com.smartwalker.service.location

import android.util.Log
import com.smartwalker.data.remote.RoadSnapApi
import com.smartwalker.domain.model.Coordinate
import com.smartwalker.domain.model.FusedPosition
import com.smartwalker.domain.model.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Singleton
class RoadSnappingService @Inject constructor(
    private val roadSnapApi: RoadSnapApi
) {

    private var currentRoute: Route? = null
    private var currentSegmentIndex: Int = 0

    fun setRoute(route: Route) {
        currentRoute = route
        currentSegmentIndex = 0
        Log.d(TAG, "Route set with ${route.waypoints.size} waypoints")
    }

    fun clearRoute() {
        currentRoute = null
        currentSegmentIndex = 0
    }

    fun snapToRoute(position: FusedPosition): SnapResult {
        val route = currentRoute ?: return SnapResult.NoRoute(position)

        if (route.waypoints.size < 2) {
            return SnapResult.NoRoute(position)
        }

        val coordinate = position.coordinate

        val searchStart = maxOf(0, currentSegmentIndex - SEGMENT_SEARCH_WINDOW)
        val searchEnd = minOf(route.waypoints.size - 1, currentSegmentIndex + SEGMENT_SEARCH_WINDOW)

        var bestSnapPoint: Coordinate? = null
        var bestDistance = Double.MAX_VALUE
        var bestSegmentIndex = currentSegmentIndex
        var bestProgressOnSegment = 0.0

        for (i in searchStart until searchEnd) {
            val segmentStart = route.waypoints[i].toCoordinate()
            val segmentEnd = route.waypoints[i + 1].toCoordinate()

            val (snapPoint, progress) = projectPointOnSegment(coordinate, segmentStart, segmentEnd)
            val distance = coordinate.distanceTo(snapPoint)

            if (distance < bestDistance) {
                bestDistance = distance
                bestSnapPoint = snapPoint
                bestSegmentIndex = i
                bestProgressOnSegment = progress
            }
        }

        if (bestDistance > MAX_SNAP_DISTANCE) {
            for (i in 0 until route.waypoints.size - 1) {
                if (i in searchStart until searchEnd) continue

                val segmentStart = route.waypoints[i].toCoordinate()
                val segmentEnd = route.waypoints[i + 1].toCoordinate()

                val (snapPoint, progress) = projectPointOnSegment(coordinate, segmentStart, segmentEnd)
                val distance = coordinate.distanceTo(snapPoint)

                if (distance < bestDistance) {
                    bestDistance = distance
                    bestSnapPoint = snapPoint
                    bestSegmentIndex = i
                    bestProgressOnSegment = progress
                }
            }
        }

        val snapPoint = bestSnapPoint ?: return SnapResult.NoRoute(position)

        if (bestDistance > MAX_SNAP_DISTANCE) {
            Log.d(TAG, "Position too far from route: ${bestDistance}m")
            return SnapResult.Deviated(
                originalPosition = position,
                nearestPointOnRoute = snapPoint,
                distanceFromRoute = bestDistance
            )
        }

        val prevSegmentIndex = currentSegmentIndex
        if (bestSegmentIndex > currentSegmentIndex) {
            currentSegmentIndex = bestSegmentIndex
        } else if (bestSegmentIndex == currentSegmentIndex && bestProgressOnSegment > 0.9) {
            if (currentSegmentIndex < route.waypoints.size - 2) {
                currentSegmentIndex++
            }
        }

        if (currentSegmentIndex != prevSegmentIndex) {
            Log.d(TAG, "Segment advanced: $prevSegmentIndex → $currentSegmentIndex (${bestDistance}m offset)")
        }

        val snappedPosition = position.copy(coordinate = snapPoint)

        return SnapResult.Snapped(
            originalPosition = position,
            snappedPosition = snappedPosition,
            distanceOffset = bestDistance,
            segmentIndex = currentSegmentIndex,
            progressOnSegment = bestProgressOnSegment
        )
    }

    /**
     * 맵 매칭용 **무상태** 투영. 전체 경로에서 가장 가까운 점을 찾되,
     * [currentSegmentIndex] 등 내부 진행 상태를 변경하지 않는다.
     *
     * snapToRoute()는 RouteDeviationDetector가 세그먼트 진행을 추적하는 데 쓰므로,
     * LocationFusionService의 위치 보정이 같은 메서드를 호출하면 진행 상태가 충돌한다.
     * 맵 매칭은 이 메서드로 진행 상태와 독립적으로 횡오차만 계산한다.
     *
     * @return 가장 가까운 경로점·횡오차(m)·세그먼트 인덱스. 경로 없으면 null.
     */
    fun projectOntoRoute(coordinate: Coordinate): RouteProjection? {
        val route = currentRoute ?: return null
        if (route.waypoints.size < 2) return null

        var bestSnap: Coordinate? = null
        var bestDistance = Double.MAX_VALUE
        var bestSegment = 0

        for (i in 0 until route.waypoints.size - 1) {
            val segmentStart = route.waypoints[i].toCoordinate()
            val segmentEnd = route.waypoints[i + 1].toCoordinate()
            val (snapPoint, _) = projectPointOnSegment(coordinate, segmentStart, segmentEnd)
            val distance = coordinate.distanceTo(snapPoint)
            if (distance < bestDistance) {
                bestDistance = distance
                bestSnap = snapPoint
                bestSegment = i
            }
        }

        val snap = bestSnap ?: return null
        return RouteProjection(snap, bestDistance, bestSegment)
    }

    /**
     * 장애물 회피 안내용: 경로(보도 중심선)가 사용자 진행방향 기준 좌/우 어느 쪽에 있고,
     * 경로에서 얼마나 벗어나 있는지 반환한다. 경로가 없으면 null.
     *
     * 시각장애인 회피 안내가 사용자를 보도 밖(차도 쪽)으로 밀지 않도록, 빈 쪽이라도
     * "경로에서 멀어지는 방향"인지 판단하는 데 쓰인다.
     *
     * @param headingDeg 사용자 진행 방향(0=북, 시계). null이면 현재 세그먼트 진행 방향을 사용.
     */
    fun routeGuidance(position: Coordinate, headingDeg: Float?): RouteGuidance? {
        val route = currentRoute ?: return null
        val proj = projectOntoRoute(position) ?: return null
        // 중심선 근처면 좌우 선호 없음(방향 추정이 불안정).
        if (proj.offsetMeters < ON_ROUTE_BAND_M) return RouteGuidance(null, proj.offsetMeters)

        val i = proj.segmentIndex
        if (i + 1 >= route.waypoints.size) return RouteGuidance(null, proj.offsetMeters)
        val segStart = route.waypoints[i].toCoordinate()
        val segEnd = route.waypoints[i + 1].toCoordinate()

        val headingRef = headingDeg?.toDouble() ?: bearing(segStart, segEnd)
        val rel = normalizeDeg(bearing(position, proj.snapped) - headingRef)
        // 경로점이 진행방향 기준 오른쪽(0~180°)이면 경로가 오른쪽에 있다.
        return RouteGuidance(routeOnRight = rel >= 0, offsetMeters = proj.offsetMeters)
    }

    /** 두 좌표 간 초기 방위각(0=북, 시계, 도). */
    private fun bearing(from: Coordinate, to: Coordinate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** 각도를 [-180, 180]로 정규화. */
    private fun normalizeDeg(d: Double): Double {
        var x = d % 360.0
        if (x > 180) x -= 360
        if (x < -180) x += 360
        return x
    }

    private fun projectPointOnSegment(
        point: Coordinate,
        segmentStart: Coordinate,
        segmentEnd: Coordinate
    ): Pair<Coordinate, Double> {
        val dx = segmentEnd.longitude - segmentStart.longitude
        val dy = segmentEnd.latitude - segmentStart.latitude
        val lengthSquared = dx * dx + dy * dy

        if (lengthSquared < 1e-12) {
            return Pair(segmentStart, 0.0)
        }

        val px = point.longitude - segmentStart.longitude
        val py = point.latitude - segmentStart.latitude
        val t = ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)

        val projectedLng = segmentStart.longitude + t * dx
        val projectedLat = segmentStart.latitude + t * dy

        return Pair(Coordinate(projectedLat, projectedLng), t)
    }

    fun getCurrentSegmentIndex(): Int = currentSegmentIndex

    fun getTotalSegments(): Int = (currentRoute?.waypoints?.size ?: 1) - 1

    fun getCurrentSegment(): Pair<Coordinate, Coordinate>? {
        val route = currentRoute ?: return null
        if (currentSegmentIndex >= route.waypoints.size - 1) return null
        return Pair(
            route.waypoints[currentSegmentIndex].toCoordinate(),
            route.waypoints[currentSegmentIndex + 1].toCoordinate()
        )
    }

    suspend fun snapToNearestRoad(position: FusedPosition): ServerSnapResult? {
        return withContext(Dispatchers.IO) {
            try {
                val response = roadSnapApi.getNearestRoad(
                    lat = position.coordinate.latitude,
                    lng = position.coordinate.longitude
                )

                val snappedPosition = position.copy(
                    coordinate = Coordinate(
                        latitude = response.snappedLat,
                        longitude = response.snappedLng
                    )
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

    suspend fun smartSnap(position: FusedPosition): FusedPosition {
        return when (val routeSnapResult = snapToRoute(position)) {
            is SnapResult.Snapped -> routeSnapResult.snappedPosition
            is SnapResult.Deviated -> {
                val serverResult = snapToNearestRoad(position)
                if (serverResult != null && serverResult.isOnRoad) serverResult.snappedPosition
                else position
            }
            is SnapResult.NoRoute -> {
                snapToNearestRoad(position)?.snappedPosition ?: position
            }
        }
    }

    companion object {
        private const val TAG = "RoadSnappingService"
        private const val MAX_SNAP_DISTANCE = 50.0
        private const val SEGMENT_SEARCH_WINDOW = 5

        /** 이 거리 이내면 경로 중심선 위로 보고 좌우 선호를 두지 않는다(m). */
        private const val ON_ROUTE_BAND_M = 2.0
    }
}

/** 무상태 맵 매칭 투영 결과. */
data class RouteProjection(
    val snapped: Coordinate,
    val offsetMeters: Double,
    val segmentIndex: Int
)

/** 장애물 회피 안내용 경로 가이던스. */
data class RouteGuidance(
    /** 경로(보도)가 진행방향 기준 오른쪽이면 true, 왼쪽이면 false, 중심선 근처면 null. */
    val routeOnRight: Boolean?,
    /** 경로 중심선으로부터의 횡오차(m). */
    val offsetMeters: Double
)

data class ServerSnapResult(
    val originalPosition: FusedPosition,
    val snappedPosition: FusedPosition,
    val distance: Double,
    val roadName: String?,
    val isOnRoad: Boolean
)

sealed class SnapResult {
    data class NoRoute(val position: FusedPosition) : SnapResult()

    data class Snapped(
        val originalPosition: FusedPosition,
        val snappedPosition: FusedPosition,
        val distanceOffset: Double,
        val segmentIndex: Int,
        val progressOnSegment: Double
    ) : SnapResult()

    data class Deviated(
        val originalPosition: FusedPosition,
        val nearestPointOnRoute: Coordinate,
        val distanceFromRoute: Double
    ) : SnapResult()

    val effectivePosition: FusedPosition
        get() = when (this) {
            is NoRoute -> position
            is Snapped -> snappedPosition
            is Deviated -> originalPosition
        }

    val isOnRoute: Boolean
        get() = this is Snapped
}
