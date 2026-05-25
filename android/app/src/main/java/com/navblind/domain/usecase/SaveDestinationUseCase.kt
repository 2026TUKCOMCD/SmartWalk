package com.navblind.domain.usecase

import com.navblind.data.local.entity.LocalDestination
import com.navblind.data.repository.DestinationRepositoryImpl
import com.navblind.domain.model.SearchResult
import javax.inject.Inject

class SaveDestinationUseCase @Inject constructor(
    private val destinationRepository: DestinationRepositoryImpl
) {
    suspend operator fun invoke(result: SearchResult): Result<LocalDestination> =
        destinationRepository.save(result)
}
