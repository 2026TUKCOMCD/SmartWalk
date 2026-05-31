package com.smartwalker.domain.usecase

import com.smartwalker.data.local.entity.LocalDestination
import com.smartwalker.data.repository.DestinationRepositoryImpl
import com.smartwalker.domain.model.SearchResult
import javax.inject.Inject

class SaveDestinationUseCase @Inject constructor(
    private val destinationRepository: DestinationRepositoryImpl
) {
    suspend operator fun invoke(result: SearchResult): Result<LocalDestination> =
        destinationRepository.save(result)
}
