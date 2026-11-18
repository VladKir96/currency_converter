package com.pocketfx

import com.pocketfx.data.local.RateStore
import com.pocketfx.data.remote.PublishedRates
import com.pocketfx.data.remote.exchangeRatesApi
import com.pocketfx.data.repository.OpenExchangeRateRepository
import com.pocketfx.data.repository.RateProblem
import com.pocketfx.domain.CachedRates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class ExchangeRateRepositoryTest {
    private val server = MockWebServer()
    private val store = TestRateStore()
    private val clock = AdjustableClock(Instant.ofEpochSecond(1_700_001_000))
    private lateinit var client: OkHttpClient
    private lateinit var repository: OpenExchangeRateRepository

    @Before fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .retryOnConnectionFailure(false)
            .callTimeout(Duration.ofSeconds(2))
            .build()
        repository = newRepository()
    }

    @After fun tearDown() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test fun successfulResponsePreservesDecimalsAndFiltersUnusableRates() = runBlocking {
        server.enqueue(response(rates = """{"USD":1,"EUR":0.123456789123456789,"RSD":108.5,"RUB":92.1,"JPY":140,"GBP":0,"ZAR":-1,"CAD":null,"AUD":"2"}"""))
        repository.refresh()
        val cache = requireNotNull(repository.latest.first())
        assertEquals(BigDecimal("0.123456789123456789"), cache.snapshot.rates["EUR"])
        assertEquals(setOf("USD", "EUR", "RSD", "RUB", "JPY"), cache.snapshot.rates.keys)
        assertEquals(1_700_000_000_000, cache.snapshot.providerUpdatedAt)
        assertEquals(1_700_086_400_000, cache.nextProviderUpdate)
        assertEquals(clock.millis(), cache.lastCheckedAt)
        assertEquals("/v6/latest/USD", server.takeRequest().path)
        assertNull(repository.status.value.problem)
    }

    @Test fun freshCacheIsReusedAndRefreshesAtTtl() = runBlocking {
        server.enqueue(response())
        repository.refresh()
        clock.advance(599)
        repository.refresh()
        assertEquals(1, server.requestCount)
        server.enqueue(response())
        clock.advance(1)
        repository.refresh()
        assertEquals(2, server.requestCount)
        assertEquals(clock.millis(), repository.latest.first()?.lastCheckedAt)
    }

    @Test fun manualRefreshBypassesTtlAndSkipsConcurrentRequest() = runBlocking {
        server.enqueue(response().setBodyDelay(400, TimeUnit.MILLISECONDS))
        val first = async { repository.refresh() }
        val request = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
        assertNotNull(request)
        assertTrue(repository.status.value.refreshing)
        repository.refresh(force = true)
        first.await()
        server.enqueue(response())
        repository.refresh(force = true)
        assertEquals(2, server.requestCount)
    }

    @Test fun networkErrorKeepsCacheAndSuccessfulTimestamp() = runBlocking {
        server.enqueue(response())
        repository.refresh()
        val original = repository.latest.first()
        clock.advance(600)
        server.enqueue(response().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        repository.refresh()
        assertEquals(RateProblem.NETWORK, repository.status.value.problem)
        assertEquals(original, repository.latest.first())
    }

    @Test fun connectionFailureWithEmptyCacheAndRecovery() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        repository.refresh()
        assertNull(repository.latest.first())
        assertEquals(RateProblem.NETWORK, repository.status.value.problem)
        server.enqueue(response())
        repository.refresh()
        assertNotNull(repository.latest.first())
        assertNull(repository.status.value.problem)
    }

    @Test fun invalidResponsesDoNotReplaceExistingRates() = runBlocking {
        server.enqueue(response())
        repository.refresh()
        val original = repository.latest.first()
        val invalid = listOf(
            MockResponse().setBody("""{"nope":]"""),
            MockResponse().setBody("""{"result":"error"}"""),
            MockResponse().setBody("""{"result":"success","base_code":"EUR","time_last_update_unix":1700000000,"rates":{"USD":1,"EUR":0.8}}"""),
            response(rates = """{"USD":0,"EUR":0.8}"""),
            response(rates = """{"USD":1,"EUR":0}"""),
            MockResponse().setResponseCode(204),
        )
        for (reply in invalid) {
            server.enqueue(reply)
            repository.refresh(force = true)
            assertEquals(RateProblem.RESPONSE, repository.status.value.problem)
            assertEquals(original, repository.latest.first())
        }
    }

    @Test fun rateLimitsAreHonoredAndServerFailuresCanBeRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "60"))
        repository.refresh()
        assertEquals(RateProblem.RATE_LIMIT, repository.status.value.problem)
        repository.refresh(force = true)
        assertEquals(1, server.requestCount)
        clock.advance(60)
        server.enqueue(MockResponse().setResponseCode(503))
        repository.refresh(force = true)
        assertEquals(RateProblem.SERVER, repository.status.value.problem)
        server.enqueue(response())
        repository.refresh()
        assertNull(repository.status.value.problem)
        assertEquals(3, server.requestCount)
    }

    @Test fun timeoutHasItsOwnStatus() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        repository.refresh()
        assertEquals(RateProblem.TIMEOUT, repository.status.value.problem)
        assertNull(repository.latest.first())
    }

    @Test fun recreatedRepositoryReadsExistingCacheAndDoesNotFetchWithinTtl() = runBlocking {
        server.enqueue(response())
        repository.refresh()
        val original = repository.latest.first()
        val recreated = newRepository()
        recreated.refresh()
        assertEquals(original, recreated.latest.first())
        assertEquals(1, server.requestCount)
    }

    private fun newRepository() = OpenExchangeRateRepository(exchangeRatesApi(server.url("/"), client), store, clock)

    private fun response(rates: String = """{"USD":1,"EUR":0.8,"RSD":100,"RUB":92.1}"""): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"result":"success","base_code":"USD","time_last_update_unix":1700000000,"time_next_update_unix":1700086400,"rates":$rates}"""
        )

    private class AdjustableClock(private var value: Instant) : Clock() {
        override fun instant() = value
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(value, zone)
        fun advance(seconds: Long) { value = value.plusSeconds(seconds) }
    }

    private class TestRateStore : RateStore {
        override val latest = MutableStateFlow<CachedRates?>(null)
        override suspend fun lastCheckedAt() = latest.value?.lastCheckedAt

        override suspend fun save(publication: PublishedRates) {
            val incoming = publication.snapshot
            val previous = latest.value?.snapshot?.takeIf { it.providerUpdatedAt >= incoming.providerUpdatedAt }
            latest.value = CachedRates(previous ?: incoming, incoming.fetchedAt, publication.nextProviderUpdate)
        }
    }
}
