package com.pocketfx.data.local

import androidx.room.withTransaction
import com.google.gson.reflect.TypeToken
import com.pocketfx.data.remote.ProviderId
import com.pocketfx.data.remote.PublishedRates
import com.pocketfx.data.remote.RateBase
import com.pocketfx.data.remote.StrictJson
import com.pocketfx.domain.CachedRates
import com.pocketfx.domain.RateSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

internal interface RateStore {
    val latest: Flow<CachedRates?>
    suspend fun lastCheckedAt(): Long?
    suspend fun save(publication: PublishedRates)
}

internal class RoomRateStore(private val database: RateDatabase) : RateStore {
    private val dao = database.rates()

    override val latest = dao.observeLatest(ProviderId, RateBase).map { row ->
        row?.let { CachedRates(it.snapshot.toSnapshot(), it.lastCheckedAt, it.nextProviderUpdate) }
    }.flowOn(Dispatchers.Default)

    override suspend fun lastCheckedAt(): Long? = dao.getCheck(ProviderId, RateBase)?.lastCheckedAt

    override suspend fun save(publication: PublishedRates) {
        val snapshot = publication.snapshot
        database.withTransaction {
            val previousPublication = dao.latestPublication(ProviderId, RateBase)
            if (previousPublication == null || snapshot.providerUpdatedAt > previousPublication) {
                dao.insert(
                    RateSnapshotEntity(
                        provider = snapshot.provider,
                        baseCurrency = snapshot.baseCurrency,
                        providerUpdatedAt = snapshot.providerUpdatedAt,
                        fetchedAt = snapshot.fetchedAt,
                        ratesJson = StrictJson.toJson(snapshot.rates.mapValues { it.value.toString() }),
                    )
                )
                dao.discardOlderRates(snapshot.provider, snapshot.baseCurrency, snapshot.providerUpdatedAt)
            }
            val next = if (previousPublication == null || snapshot.providerUpdatedAt >= previousPublication) {
                publication.nextProviderUpdate
                    ?: dao.getCheck(ProviderId, RateBase)?.nextProviderUpdate?.takeIf {
                        snapshot.providerUpdatedAt == previousPublication
                    }
            } else {
                dao.getCheck(ProviderId, RateBase)?.nextProviderUpdate
            }
            dao.recordCheck(RateCheckEntity(ProviderId, RateBase, snapshot.fetchedAt, next))
        }
    }

    private fun RateSnapshotEntity.toSnapshot(): RateSnapshot {
        val rates = StrictJson.fromJson(ratesJson, StringRates).mapValues { it.value.toBigDecimal() }
        return RateSnapshot(provider, baseCurrency, providerUpdatedAt, fetchedAt, rates)
    }

    private companion object {
        val StringRates = object : TypeToken<Map<String, String>>() {}
    }
}
