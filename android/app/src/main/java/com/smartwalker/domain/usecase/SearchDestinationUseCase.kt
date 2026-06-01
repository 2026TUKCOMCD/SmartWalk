package com.smartwalker.domain.usecase

import com.smartwalker.domain.model.Coordinate
import com.smartwalker.domain.model.SearchResult
import com.smartwalker.domain.repository.NavigationRepository
import javax.inject.Inject

class SearchDestinationUseCase @Inject constructor(
    private val navigationRepository: NavigationRepository
) {
    suspend operator fun invoke(
        query: String,
        currentLocation: Coordinate? = null,
        limit: Int = 10
    ): Result<List<SearchResult>> {
        if (query.length < 2) {
            return Result.success(emptyList())
        }
        return navigationRepository.searchDestinations(query, currentLocation, limit)
    }
}
