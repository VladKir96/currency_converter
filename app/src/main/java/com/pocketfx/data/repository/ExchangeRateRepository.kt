package com.pocketfx.data.repository

import com.google.gson.JsonParseException
import com.pocketfx.data.local.RateStore
import com.pocketfx.data.remote.ExchangeRatesApi
import com.pocketfx.data.remote.InvalidRatesException
import com.pocketfx.data.remote.toPublishedRates
import com.pocketfx.domain.CachedRates
import com.pocketfx.domain.RateSnapshot
import com.pocketfx.domain.isCacheFresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal enum class RateProblem { OFFLINE, TIMEOUT, RATE_LIMIT, SERVER, RESPONSE, NETWORK, STORAGE }

internal data class RefreshStatus(
    val refreshing: Boolean = false,
    val problem: RateProblem? = null,
)

internal interface ExchangeRateRepository {
    val latest: Flow<CachedRates?>
    val status: StateFlow<RefreshStatus>
    fun history(since: Long): Flow<List<RateSnapshot>>
    suspend fun refresh(force: Boolean = false)
}

internal class OpenExchangeRateRepository(
    private val api: ExchangeRatesApi,
    private val store: RateStore,
    private val clock: Clock,
) : ExchangeRateRepository {
    private val lock = Mutex()
    private val refreshState = MutableStateFlow(RefreshStatus())
    private var retryAt: Long? = null

    override val latest = store.latest
    override val status: StateFlow<RefreshStatus> = refreshState
    override fun history(since: Long) = store.history(since)

    override suspend fun refresh(force: Boolean) {
        if (!lock.tryLock()) return
        try {
            val now = clock.millis()
            if (!force && isCacheFresh(store.lastCheckedAt(), now)) return
            if (retryAt?.let { now < it } == true) {
                refreshState.value = RefreshStatus(problem = RateProblem.RATE_LIMIT)
                return
            }
            refreshState.value = RefreshStatus(refreshing = true)
            val response = try {
                val response = api.latest()
                if (!response.isSuccessful) throw HttpException(response)
                response.body() ?: throw InvalidRatesException()
            } catch (error: Exception) {
                refreshState.value = RefreshStatus(problem = networkProblem(error))
                return
            }
            val publication = withContext(Dispatchers.Default) {
                response.toPublishedRates(clock.millis())
            }
            store.save(publication)
            retryAt = null
            refreshState.value = RefreshStatus()
        } catch (error: CancellationException) {
            throw error
        } catch (_: JsonParseException) {
            refreshState.value = RefreshStatus(problem = RateProblem.RESPONSE)
        } catch (_: InvalidRatesException) {
            refreshState.value = RefreshStatus(problem = RateProblem.RESPONSE)
        } catch (_: Exception) {
            refreshState.value = RefreshStatus(problem = RateProblem.STORAGE)
        } finally {
            if (refreshState.value.refreshing) refreshState.value = RefreshStatus()
            lock.unlock()
        }
    }

    private fun networkProblem(error: Exception): RateProblem = when (error) {
        is CancellationException -> throw error
        is HttpException -> when (error.code()) {
            429 -> {
                retryAt = retryTime(error.response()?.headers()?.get("Retry-After"))
                RateProblem.RATE_LIMIT
            }
            in 500..599 -> RateProblem.SERVER
            else -> RateProblem.RESPONSE
        }
        is UnknownHostException, is ConnectException -> RateProblem.OFFLINE
        is InterruptedIOException -> RateProblem.TIMEOUT
            is IOException -> RateProblem.NETWORK
        else -> RateProblem.RESPONSE
    }

    private fun retryTime(header: String?): Long {
        val now = clock.instant()
        val delay = header?.toLongOrNull()?.takeIf { it in 0..86_400 }
        val date = header?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
        }
        return (delay?.let(now::plusSeconds) ?: date ?: now.plus(Duration.ofMinutes(20))).toEpochMilli()
    }
}
