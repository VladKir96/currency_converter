package com.pocketfx

import com.pocketfx.data.local.HistoryCatalog
import com.pocketfx.data.local.HistoryStore
import com.pocketfx.data.remote.frankfurterApi
import com.pocketfx.data.repository.FrankfurterHistoryRepository
import com.pocketfx.data.repository.HistoryProblem
import com.pocketfx.data.repository.HistoryState
import com.pocketfx.domain.HistoryPoint
import com.pocketfx.domain.HistoryQuery
import com.pocketfx.domain.HistorySeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FrankfurterHistoryRepositoryTest {
    private val server = MockWebServer()
    private val clock = HistoryClock(Instant.parse("2026-11-18T12:00:00Z"))
    private val store = MemoryHistoryStore()
    private val today = LocalDate.of(2026, 11, 18)
    private val week = HistoryQuery.endingOn("EUR", "USD", 7, today)
    private lateinit var client: OkHttpClient
    private lateinit var repository: FrankfurterHistoryRepository

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .callTimeout(Duration.ofSeconds(3))
            .retryOnConnectionFailure(false)
            .build()
        repository = newRepository()
    }

    @After fun tearDown() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test fun parsesExactDecimalsAndSortsOnlyPublishedDatesWithinTheRequestedWindow() = runBlocking {
        server.enqueue(rows(
            rate("2026-11-16", "1.125000123456789"),
            rate("2026-11-13", "1.111"),
            rate("2026-11-11", "1.00"),
            rate("2026-11-20", "1.10"),
        ))
        val result = repository.load(week, force = false).last()
        assertNull(result.problem)
        assertEquals(
            listOf(
                HistoryPoint(LocalDate.parse("2026-11-13"), BigDecimal("1.111")),
                HistoryPoint(LocalDate.parse("2026-11-16"), BigDecimal("1.125000123456789")),
            ),
            result.series?.points,
        )
    }

    @Test fun sendsInclusiveCalendarRangesForAllPeriodsAndReusesEachOne() = runBlocking {
        for ((days, from) in listOf(7 to "2026-11-12", 30 to "2026-10-20", 90 to "2026-08-21")) {
            server.enqueue(rows(rate("2026-11-17", "91.23", quote = "RUB")))
            val query = HistoryQuery.endingOn("EUR", "RUB", days, today)
            assertEquals(query, repository.load(query, false).last().series?.query)
            val url = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)?.requestUrl)
            assertEquals("/v2/rates", url.encodedPath)
            assertEquals("EUR", url.queryParameter("base"))
            assertEquals("RUB", url.queryParameter("quotes"))
            assertEquals(from, url.queryParameter("from"))
            assertEquals("2026-11-18", url.queryParameter("to"))
            assertEquals(query, repository.load(query, false).last().series?.query)
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun distinguishesEmptyHistoryFromUnsupportedCurrencies() = runBlocking {
        server.enqueue(rows())
        val empty = repository.load(week, false).last()
        assertEquals(emptyList<HistoryPoint>(), empty.series?.points)
        assertNull(empty.problem)
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"invalid currency: ZZZ"}"""))
        val unsupported = repository.load(week.copy(target = "ZZZ"), false).last()
        assertEquals(HistoryProblem.UNSUPPORTED, unsupported.problem)
        assertNull(unsupported.series)
    }

    @Test fun malformedOrWrongPairObservationsDoNotReplaceSavedData() = runBlocking {
        val expected = seed()
        for (invalid in listOf(
            MockResponse().setBody("{"),
            rows(rate("2026-11-17", "1.23", quote = "RSD")),
            rows(rate("2026-11-17", "0")),
            rows(rate("2026-11-17", "1.23"), rate("2026-11-17", "1.22")),
        )) {
            server.enqueue(invalid)
            val result = repository.load(week, true).last()
            assertEquals(HistoryProblem.LOAD_FAILED, result.problem)
            assertEquals(expected, result.series)
            assertEquals(expected, store.exact(week))
        }
    }

    @Test fun reusesSavedRangeUntilExactly24HoursAndHonorsManualRefresh() = runBlocking {
        val expected = seed()
        clock.advance(Duration.ofHours(24).minusMillis(1))
        assertEquals(expected, newRepository().load(week, false).last().series)
        assertEquals(1, server.requestCount)
        clock.advance(Duration.ofMillis(1))
        server.enqueue(rows(rate("2026-11-18", "1.15")))
        val refreshed = repository.load(week, false).toList()
        assertEquals(HistoryState(expected), refreshed.first())
        assertEquals(BigDecimal("1.15"), refreshed.last().series?.points?.single()?.rate)
        server.enqueue(rows(rate("2026-11-18", "1.16")))
        assertEquals(BigDecimal("1.16"), repository.load(week, true).last().series?.points?.single()?.rate)
        assertEquals(3, server.requestCount)
    }

    @Test fun networkAndHttpFailuresPreserveSavedHistoryAcrossCalendarRollover() = runBlocking {
        val expected = seed()
        server.enqueue(rows(rate("2026-11-18", "1.4")).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val network = repository.load(week, true).last()
        assertEquals(expected, network.series)
        assertEquals(HistoryProblem.LOAD_FAILED, network.problem)
        clock.advance(Duration.ofDays(1))
        val tomorrow = HistoryQuery.endingOn("EUR", "USD", 7, today.plusDays(1))
        server.enqueue(MockResponse().setResponseCode(503))
        val out = newRepository().load(tomorrow, false).toList()
        assertTrue(out.first().loading)
        assertEquals(expected, out.first().series)
        assertFalse(out.last().loading)
        assertEquals(expected, out.last().series)
        assertEquals(HistoryProblem.LOAD_FAILED, out.last().problem)
        assertNull(store.exact(tomorrow))
    }

    @Test fun concurrentLoadsOnlyDownloadAnUncachedRangeOnce() = runBlocking {
        server.enqueue(rows(rate("2026-11-18", "1.23")).setBodyDelay(350, TimeUnit.MILLISECONDS))
        val first = async { repository.load(week, false).last() }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
        val second = async { repository.load(week, false).last() }
        assertEquals(first.await(), second.await())
        assertEquals(1, server.requestCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun changingPairCancelsOutdatedRequestBeforeItCanReplaceTheSelectedSeries() = runBlocking {
        server.enqueue(rows(rate("2026-11-18", "1.23")).setBodyDelay(2, TimeUnit.SECONDS))
        server.enqueue(rows(rate("2026-11-18", "117.49", quote = "RSD")))
        val selection = MutableStateFlow(week)
        val state = async {
            withTimeout(5_000) {
                selection.flatMapLatest { repository.load(it, false) }.first { !it.loading }
            }
        }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
        val changed = week.copy(target = "RSD")
        selection.value = changed
        assertEquals(changed, state.await().series?.query)
        assertEquals(BigDecimal("117.49"), store.exact(changed)?.points?.single()?.rate)
        assertNull(store.exact(week))
    }

    @Test fun currencyCatalogUsesSupportedFiatCodesAndIsReused() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            [{"iso_code":"RUB","type":"currency"},{"iso_code":"KPW","type":"currency"},
            {"iso_code":"RSD","type":"currency"},{"iso_code":"BTC","type":"crypto"}]
        """.trimIndent()))
        val codes = listOf("KPW", "RSD", "RUB")
        assertEquals(codes, repository.currencies().last())
        assertEquals("/v2/currencies", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        assertEquals(codes, newRepository().currencies().last())
        assertEquals(1, server.requestCount)
    }

    private suspend fun seed(): HistorySeries {
        server.enqueue(rows(rate("2026-11-17", "1.125")))
        return requireNotNull(repository.load(week, false).last().series)
    }

    private fun newRepository() = FrankfurterHistoryRepository(frankfurterApi(server.url("/"), client), store, clock)

    private fun rate(day: String, value: String, quote: String = "USD") =
        """{"date":"$day","base":"EUR","quote":"$quote","rate":$value}"""

    private fun rows(vararg values: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(values.joinToString(",", "[", "]"))

    private class HistoryClock(private var time: Instant) : Clock() {
        override fun instant() = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(time, zone)
        fun advance(duration: Duration) { time = time.plus(duration) }
    }

    private class MemoryHistoryStore : HistoryStore {
        private val series = ConcurrentHashMap<HistoryQuery, HistorySeries>()
        private var catalog: HistoryCatalog? = null
        override suspend fun exact(query: HistoryQuery) = series[query]
        override suspend fun earlier(query: HistoryQuery): HistorySeries? = series.values.filter {
            val previous = it.query
            it.points.isNotEmpty() && previous.to < query.to &&
                previous.source == query.source && previous.target == query.target &&
                previous.to.toEpochDay() - previous.from.toEpochDay() == query.to.toEpochDay() - query.from.toEpochDay()
        }.maxByOrNull { it.query.to }

        override suspend fun save(series: HistorySeries) { this.series[series.query] = series }
        override suspend fun catalog() = catalog
        override suspend fun saveCatalog(catalog: HistoryCatalog) { this.catalog = catalog }
    }
}
