package com.navblind.domain.repository

import com.navblind.domain.model.Coordinate
import com.navblind.domain.model.Route
import com.navblind.domain.model.SearchResult
import java.util.UUID

interface NavigationRepository {
    suspend fun calculateRoute(
        origin: Coordinate,
        destination: Coordinate,
        destName: String? = null
    ): Result<Route>

    suspend fun reroute(
        sessionId: UUID,
        currentLocation: Coordinate
    ): Result<Route>

    suspend fun searchDestinations(
        query: String,
        currentLocation: Coordinate? = null,
        limit: Int = 10
    ): Result<List<SearchResult>>
}
