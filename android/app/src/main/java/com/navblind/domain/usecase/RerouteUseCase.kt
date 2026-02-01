package com.navblind.domain.usecase

import com.navblind.domain.model.Coordinate
import com.navblind.domain.model.Route
import com.navblind.domain.repository.NavigationRepository
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
