package com.smartwalker.domain.usecase

import com.smartwalker.domain.model.Coordinate
import com.smartwalker.domain.model.Route
import com.smartwalker.domain.repository.NavigationRepository
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
