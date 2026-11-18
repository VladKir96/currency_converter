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

        val exact = loadOrNull { store.exact(query) }
        if (!force && exact?.let { isFresh(it.fetchedAt) } == true) {
            emit(HistoryState(exact, loading = false))
            return@flow
        }

        val saved = exact ?: loadOrNull { store.earlier(query) }
        emit(HistoryState(saved))
        val state = seriesLock.withLock {
            val current = loadOrNull { store.exact(query) }
            if (current != null && (current != exact || !force && isFresh(current.fetchedAt))) {
                HistoryState(current, loading = false)
            } else {
                fetch(query, current ?: saved)
            }
        }
        emit(state)
    }

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

    private suspend fun fetch(query: HistoryQuery, saved: HistorySeries?): HistoryState {
        val series = try {
            val response = api.rates(query.source, query.target, query.from.toString(), query.to.toString())
            response.errorBody()?.close()
            if (response.code() == 404 || response.code() == 422) {
                return HistoryState(loading = false, problem = HistoryProblem.UNSUPPORTED)
            }

            val points = response.body()?.use { body ->
                withContext(Dispatchers.Default) { body.readHistory(query) }
            } ?: return HistoryState(saved, loading = false, problem = HistoryProblem.LOAD_FAILED)
            HistorySeries(query, clock.millis(), points)
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
            HistoryState(series, loading = false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            HistoryState(series, loading = false, problem = HistoryProblem.STORAGE)
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
    }
}
