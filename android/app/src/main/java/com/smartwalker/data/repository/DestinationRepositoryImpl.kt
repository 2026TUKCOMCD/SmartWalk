package com.smartwalker.data.repository

import com.smartwalker.data.local.dao.DestinationDao
import com.smartwalker.data.local.entity.LocalDestination
import com.smartwalker.data.remote.DestinationApi
import com.smartwalker.domain.model.SearchResult
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

class DestinationRepositoryImpl @Inject constructor(
    private val destinationApi: DestinationApi,
    private val destinationDao: DestinationDao
) {
    fun observeSaved(): Flow<List<LocalDestination>> = destinationDao.observeAll()

    suspend fun save(result: SearchResult): Result<LocalDestination> = runCatching {
        val local = LocalDestination(
            id = UUID.randomUUID(),
            name = result.name,
            latitude = result.latitude,
            longitude = result.longitude,
            address = result.address,
            label = null
        )
        destinationDao.insert(local)
        local
    }

    suspend fun delete(id: UUID): Result<Unit> = runCatching {
        destinationDao.deleteById(id)
    }

    suspend fun incrementUseCount(id: UUID) {
        destinationDao.incrementUseCount(id)
    }

    suspend fun syncFromRemote(): Result<Unit> = runCatching {
        val remoteList = destinationApi.getDestinations()
        val locals = remoteList.map { dto ->
            LocalDestination(
                id = dto.id,
                name = dto.name,
                latitude = dto.latitude,
                longitude = dto.longitude,
                address = dto.address,
                label = dto.label,
                useCount = dto.useCount,
                synced = true
            )
        }
        destinationDao.insertAll(locals)
    }
}
