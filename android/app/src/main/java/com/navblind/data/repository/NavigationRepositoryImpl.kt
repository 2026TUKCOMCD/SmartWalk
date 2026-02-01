package com.navblind.data.repository

import com.navblind.data.remote.*
import com.navblind.domain.model.*
import com.navblind.domain.repository.NavigationRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationRepositoryImpl @Inject constructor(
    private val navigationApi: NavigationApi,
    private val destinationApi: DestinationApi
) : NavigationRepository {

    override suspend fun calculateRoute(
        origin: Coordinate,
        destination: Coordinate,
        destName: String?
    ): Result<Route> {
        return try {
            val request = RouteRequest(
                originLat = origin.latitude,
                originLng = origin.longitude,
                destLat = destination.latitude,
                destLng = destination.longitude,
                destName = destName
            )
            val response = navigationApi.calculateRoute(request)
            Result.success(response.toRoute())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun reroute(
        sessionId: UUID,
        currentLocation: Coordinate
    ): Result<Route> {
        return try {
            val request = RerouteRequest(
                sessionId = sessionId,
                currentLat = currentLocation.latitude,
                currentLng = currentLocation.longitude
            )
            val response = navigationApi.reroute(request)
            Result.success(response.toRoute())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun searchDestinations(
        query: String,
        currentLocation: Coordinate?,
        limit: Int
    ): Result<List<SearchResult>> {
        return try {
            val response = destinationApi.search(
                query = query,
                lat = currentLocation?.latitude,
                lng = currentLocation?.longitude,
                limit = limit
            )
            Result.success(response.results.map { it.toSearchResult() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun RouteResponse.toRoute(): Route {
        return Route(
            sessionId = sessionId,
            distance = distance,
            duration = duration,
            waypoints = waypoints.map { it.toWaypoint() },
            instructions = instructions.map { it.toInstruction() }
        )
    }

    private fun WaypointDto.toWaypoint(): Waypoint {
        return Waypoint(
            lat = lat,
            lng = lng,
            name = name
        )
    }

    private fun InstructionDto.toInstruction(): Instruction {
        return Instruction(
            step = step,
            type = InstructionType.fromString(type),
            modifier = TurnModifier.fromString(modifier),
            text = text,
            distance = distance,
            location = location.toWaypoint()
        )
    }

    private fun SearchResultDto.toSearchResult(): SearchResult {
        return SearchResult(
            name = name,
            latitude = latitude,
            longitude = longitude,
            address = address,
            distance = distance,
            category = category
        )
    }
}
