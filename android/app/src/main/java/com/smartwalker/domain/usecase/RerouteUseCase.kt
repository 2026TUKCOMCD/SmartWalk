package com.smartwalker.domain.usecase

import com.smartwalker.domain.model.Coordinate
import com.smartwalker.domain.model.Route
import com.smartwalker.domain.repository.NavigationRepository
import java.util.UUID
import javax.inject.Inject

class RerouteUseCase @Inject constructor(
    private val navigationRepository: NavigationRepository
) {
    suspend operator fun invoke(
        sessionId: UUID,
        currentLocation: Coordinate
    ): Result<Route> {
        return navigationRepository.reroute(sessionId, currentLocation)
    }
}
