package com.navblind.service.location

import android.util.Log
import com.navblind.data.remote.RoadSnapApi
import com.navblind.domain.model.Coordinate
import com.navblind.domain.model.FusedPosition
import com.navblind.domain.model.Route
import com.navblind.domain.model.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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
    }
}

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
