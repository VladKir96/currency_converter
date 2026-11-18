package com.pocketfx.data.repository

import com.pocketfx.data.local.HistoryCatalog
import com.pocketfx.data.local.HistoryStore
import com.pocketfx.data.remote.FrankfurterApi
import com.pocketfx.data.remote.readHistory
import com.pocketfx.data.remote.readHistoryCurrencies
import com.pocketfx.domain.HistoryQuery
import com.pocketfx.domain.HistorySeries
import com.pocketfx.domain.isCurrencyCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Clock
import java.time.Duration

internal enum class HistoryProblem { UNSUPPORTED, OFFLINE, LOAD_FAILED, STORAGE }

internal data class HistoryState(
    val series: HistorySeries? = null,
    val loading: Boolean = true,
    val problem: HistoryProblem? = null,
)

internal interface HistoricalRatesRepository {
    fun load(query: HistoryQuery, force: Boolean): Flow<HistoryState>
    fun currencies(): Flow<List<String>>
}

internal class FrankfurterHistoryRepository(
    private val api: FrankfurterApi,
    private val store: HistoryStore,
    private val clock: Clock,
) : HistoricalRatesRepository {
    private val seriesLock = Mutex()
    private val catalogLock = Mutex()

    override fun load(query: HistoryQuery, force: Boolean): Flow<HistoryState> = flow {
        if (!isCurrencyCode(query.source) || !isCurrencyCode(query.target) || query.source == query.target) {
            emit(HistoryState(loading = false, problem = HistoryProblem.UNSUPPORTED))
            return@flow
        }

        val cached = coveringSeries(query)
        if (!force && cached?.let { isFresh(it.fetchedAt) } == true) {
            emit(HistoryState(cached.forQuery(query, slice = true), loading = false))
            return@flow
        }

        val earlier = if (cached == null) earlierSeries(query) else null
        val saved = cached?.forQuery(query, slice = true) ?: earlier?.forQuery(query, slice = false)
        emit(HistoryState(saved))
        val state = seriesLock.withLock {
            val current = coveringSeries(query)
            if (current != null && (current != cached || !force && isFresh(current.fetchedAt))) {
                HistoryState(current.forQuery(query, slice = true), loading = false)
            } else {
                val existing = current ?: cached ?: earlier
                val upstream = if (existing?.query?.source == query.target) query.reversed() else query
                fetch(upstream, query, saved)
            }
        }
        emit(state)
    }.flowOn(Dispatchers.Default)

    private suspend fun coveringSeries(query: HistoryQuery): HistorySeries? {
        val reverse = query.reversed()
        val direct = loadOrNull { store.exact(query) }
        val inverse = loadOrNull { store.exact(reverse) }
        val exact = listOfNotNull(direct, inverse).filter { it.points.isNotEmpty() }
        val newestExact = exact.maxWithOrNull(ByFetchTime)
        if (newestExact != null && isFresh(newestExact.fetchedAt)) return newestExact

        // Both directions choose the same underlying observations, including when both were cached.
        return (exact + listOfNotNull(
            loadOrNull { store.covering(query) },
            loadOrNull { store.covering(reverse) },
        )).maxWithOrNull(ByFetchTime) ?: direct
    }

    private suspend fun earlierSeries(query: HistoryQuery): HistorySeries? =
        listOfNotNull(loadOrNull { store.earlier(query) }, loadOrNull { store.earlier(query.reversed()) })
            .maxWithOrNull(compareBy<HistorySeries> { it.query.to }.then(ByFetchTime))

    override fun currencies(): Flow<List<String>> = flow {
        val cached = loadOrNull { store.catalog() }
        if (cached != null) emit(cached.codes)
        if (cached?.let { isFresh(it.fetchedAt) } == true) return@flow

        val updated = catalogLock.withLock {
            val current = loadOrNull { store.catalog() }
            if (current?.let { isFresh(it.fetchedAt) } == true) {
                current.codes
            } else {
                loadOrNull {
                    val response = api.currencies()
                    response.errorBody()?.close()
                    val codes = response.body()?.use { body ->
                        withContext(Dispatchers.Default) { body.readHistoryCurrencies() }
                    } ?: return@loadOrNull null
                    store.saveCatalog(HistoryCatalog(clock.millis(), codes))
                    codes
                }
            }
        }
        if (updated != null && updated != cached?.codes) emit(updated)
    }

    private suspend fun fetch(upstream: HistoryQuery, display: HistoryQuery, saved: HistorySeries?): HistoryState {
        val series = try {
            val response = api.rates(upstream.source, upstream.target, upstream.from.toString(), upstream.to.toString())
            response.errorBody()?.close()
            if (response.code() == 404 || response.code() == 422) {
                return HistoryState(loading = false, problem = HistoryProblem.UNSUPPORTED)
            }

            val points = response.body()?.use { body ->
                withContext(Dispatchers.Default) { body.readHistory(upstream) }
            } ?: return HistoryState(saved, loading = false, problem = HistoryProblem.LOAD_FAILED)
            HistorySeries(upstream, clock.millis(), points)
        } catch (error: CancellationException) {
            throw error
        } catch (_: UnknownHostException) {
            return HistoryState(saved, loading = false, problem = HistoryProblem.OFFLINE)
        } catch (_: ConnectException) {
            return HistoryState(saved, loading = false, problem = HistoryProblem.OFFLINE)
        } catch (_: Exception) {
            return HistoryState(saved, loading = false, problem = HistoryProblem.LOAD_FAILED)
        }

        return try {
            store.save(series)
            HistoryState(series.forQuery(display, slice = true), loading = false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            HistoryState(series.forQuery(display, slice = true), loading = false, problem = HistoryProblem.STORAGE)
        }
    }

    private fun isFresh(fetchedAt: Long): Boolean =
        clock.millis() - fetchedAt in 0 until CacheTtlMillis

    private suspend fun <T> loadOrNull(read: suspend () -> T?): T? = try {
        read()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private companion object {
        val CacheTtlMillis = Duration.ofHours(24).toMillis()
        val ByFetchTime = compareBy<HistorySeries> { it.fetchedAt }.thenBy { it.query.source }
    }
}
