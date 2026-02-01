package com.navblind.domain.usecase

import com.navblind.domain.model.Coordinate
import com.navblind.domain.model.Route
import com.navblind.domain.repository.NavigationRepository
import javax.inject.Inject

class StartNavigationUseCase @Inject constructor(
    private val navigationRepository: NavigationRepository
) {
    suspend operator fun invoke(
        origin: Coordinate,
        destination: Coordinate,
        destName: String? = null
    ): Result<Route> {
        return navigationRepository.calculateRoute(origin, destination, destName)
    }
}
