package com.pocketfx

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketfx.data.preferences.UserPreferencesStore
import com.pocketfx.data.repository.ExchangeRateRepository
import com.pocketfx.data.repository.HistoricalRatesRepository
import com.pocketfx.data.repository.HistoryProblem
import com.pocketfx.data.repository.HistoryState
import com.pocketfx.data.repository.RateProblem
import com.pocketfx.data.repository.RefreshStatus
import com.pocketfx.domain.CachedRates
import com.pocketfx.domain.HistoryPeriod
import com.pocketfx.domain.HistoryPoint
import com.pocketfx.domain.HistoryQuery
import com.pocketfx.domain.HistorySeries
import com.pocketfx.domain.RateSnapshot
import com.pocketfx.domain.UserPreferences
import com.pocketfx.ui.PocketFxApp
import com.pocketfx.ui.PocketFxViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PocketFxUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val dataJob = Job()
    private val clock = Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var file: File
    private lateinit var preferenceStore: UserPreferencesStore
    private val conversionRequests = AtomicInteger()
    private val rates = mapOf("USD" to "1", "EUR" to "0.8", "RSD" to "100", "RUB" to "92.1", "JPY" to "140", "GBP" to "0.5")
        .mapValues { it.value.toBigDecimal() }
    private val snapshot = RateSnapshot("test", "USD", clock.millis() - 1000, clock.millis(), rates)
    private val cached = CachedRates(snapshot, clock.millis(), clock.millis() + 1000)

    @After fun dispose() {
        compose.runOnIdle { models.clear() }
        runBlocking { dataJob.cancelAndJoin() }
        if (::file.isInitialized) file.delete()
    }

    @Test fun amountEditsRecalculateAllDefaultResults() {
        show()
        compose.onNodeWithTag("result-USD").assertTextEquals("125.00")
        compose.onNodeWithTag("result-RSD").assertTextEquals("12,500.00")
        compose.onNodeWithTag("result-RUB").assertTextEquals("11,512.50")
        compose.onNodeWithTag("source-amount").performTextReplacement("20,5")
        compose.onNodeWithTag("result-USD").assertTextEquals("25.62")
        compose.onNodeWithTag("result-RSD").assertTextEquals("2,562.50")
        compose.onNodeWithTag("result-RUB").assertTextEquals("2,360.06")
    }

    @Test fun offlineCalculatorAppliesPrecedenceWithoutRefreshingRates() {
        show(connected = false)
        val checks = conversionRequests.get()
        compose.onNodeWithContentDescription("Calculator").performClick()
        compose.onNodeWithTag("calculator-expression").assertTextEquals("100")
        for (key in listOf("ADD", "TWO", "FIVE", "MULTIPLY", "TWO", "EQUALS")) {
            compose.onNodeWithTag("calc-$key").performClick()
        }
        compose.onNodeWithTag("calculator-result").assertTextEquals("150")
        compose.onNodeWithText("Apply to converter").performScrollTo().performClick()
        compose.onNodeWithTag("source-amount").assertTextEquals("150")
        compose.onNodeWithTag("result-USD").assertTextEquals("187.50")
        assertEquals(checks, conversionRequests.get())
        assertResultOrder("USD", "RSD", "RUB")
    }

    @Test fun closingCalculatorDiscardsTheDraftAndInvalidApplyIsDisabled() {
        show()
        compose.onNodeWithContentDescription("Calculator").performClick()
        compose.onNodeWithTag("calc-DIVIDE").performClick()
        compose.onNodeWithTag("calc-ZERO").performClick()
        compose.onNodeWithTag("calc-EQUALS").performClick()
        compose.onNodeWithText("Apply to converter").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close calculator").performClick()
        compose.onNodeWithTag("source-amount").assertTextEquals("100")
        compose.onNodeWithContentDescription("Calculator").performClick()
        compose.onNodeWithTag("calculator-expression").assertTextEquals("100")
    }

    @Test fun clearsAmountWithoutChangingCurrenciesOrRefreshingRates() {
        show()
        val amount = compose.onNodeWithTag("source-amount")
        compose.onNodeWithContentDescription("Clear amount").assertIsDisplayed()
        val priorChecks = conversionRequests.get()
        amount.performClick()
        compose.onNodeWithContentDescription("Clear amount").performClick()
        amount.assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithContentDescription("Clear amount").assertDoesNotExist()
        for (code in listOf("USD", "RSD", "RUB")) {
            compose.onNodeWithTag("result-$code").assertTextEquals("—")
        }
        compose.onNodeWithTag("source-picker").assertTextEquals("€", "EUR")
        amount.performTextInput("10")
        compose.onNodeWithTag("result-USD").assertTextEquals("12.50")
        assertEquals(priorChecks, conversionRequests.get())
    }

    @Test fun selectingTargetAsSourceReplacesItAndSwitchingBackRestoresOrder() {
        show()
        compose.onNodeWithTag("source-picker").performClick()
        pick("USD").performClick()
        waitForText("10,000.00")
        compose.onNodeWithTag("result-USD").assertDoesNotExist()
        compose.onNodeWithTag("result-EUR").assertTextEquals("80.00")
        assertResultOrder("EUR", "RSD", "RUB")
        compose.onNodeWithTag("source-picker").performClick()
        pick("EUR").performClick()
        waitForText("125.00")
        compose.onNodeWithTag("result-EUR").assertDoesNotExist()
        assertResultOrder("USD", "RSD", "RUB")
    }

    @Test fun switchingToANewSourceAppendsThePreviousSource() {
        show()
        compose.onNodeWithTag("source-picker").performClick()
        pick("GBP").performClick()
        waitForText("160.00")
        assertResultOrder("USD", "RSD", "RUB", "EUR")
        compose.onNodeWithTag("result-GBP").assertDoesNotExist()
    }

    private fun assertResultOrder(vararg codes: String) {
        val y = codes.map { compose.onNodeWithTag("result-$it").fetchSemanticsNode().boundsInRoot.top }
        assertTrue(y.zipWithNext().all { (earlier, later) -> earlier < later })
    }

    @Test fun longPressDragReordersAndPreservesTheOrderWhenSwitchingSource() {
        show()
        compose.onNodeWithTag("drag-RUB").performTouchInput { click() }
        assertResultOrder("USD", "RSD", "RUB")
        dragAbove("RUB", "USD")
        waitForTargets(listOf("RUB", "USD", "RSD"))
        assertResultOrder("RUB", "USD", "RSD")
        compose.onNodeWithTag("source-picker").performClick()
        pick("USD").performClick()
        waitForTargets(listOf("RUB", "EUR", "RSD"))
        assertResultOrder("RUB", "EUR", "RSD")
        compose.onNodeWithTag("drag-USD").assertDoesNotExist()
    }

    @Test fun draggingWorksAfterScrollingToLaterTargets() {
        val targets = listOf("USD", "RSD", "RUB", "GBP", "JPY", "CAD", "AUD", "NZD", "CHF", "PLN", "CNY", "HKD", "HUF", "NOK", "SEK")
        show(prefs = UserPreferences(targets = targets))
        compose.onNodeWithTag("converter-list").performScrollToKey("target-HKD")
        dragAbove("NOK", "HKD")
        val expected = targets.toMutableList()
        expected.remove("NOK")
        expected.add(expected.indexOf("HKD"), "NOK")
        waitForTargets(expected)
        assertResultOrder("NOK", "HKD", "HUF")
    }

    @Test fun holdingADragNearTheBottomAutomaticallyScrollsThroughTargets() {
        val targets = listOf(
            "USD", "RSD", "RUB", "GBP", "JPY", "CAD", "AUD", "NZD", "CHF", "PLN", "CNY", "HKD",
            "HUF", "NOK", "SEK", "DKK", "CZK", "TRY", "UAH", "INR", "BRL", "MXN", "ARS", "CLP",
        )
        show(prefs = UserPreferences(targets = targets))
        val list = compose.onNodeWithTag("converter-list")
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("drag-USD").fetchSemanticsNode().boundsInRoot.center - viewport.topLeft
        compose.onNodeWithTag("target-TRY").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput {
                down(start)
                advanceEventTime(650)
                moveTo(start)
                moveTo(Offset(start.x, viewport.height - 12f))
            }
            compose.mainClock.advanceTimeBy(1600)
            list.performTouchInput { up() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(5000) {
            runBlocking { preferenceStore.values.first().targets.indexOf("USD") >= targets.indexOf("TRY") }
        }
        val reordered = runBlocking { preferenceStore.values.first().targets }
        assertEquals(targets.drop(1), reordered.filterNot { it == "USD" })
    }

    @Test fun longPressingAmountCopiesWithoutReorderingAndMenuStillMovesRows() {
        show()
        compose.onNodeWithTag("result-RSD").performTouchInput { longClick() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            assertEquals("12,500.00", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        assertResultOrder("USD", "RSD", "RUB")
        compose.onNodeWithContentDescription("Options for RUB").performClick()
        compose.onNodeWithText("Move up").performClick()
        waitForTargets(listOf("USD", "RUB", "RSD"))
        assertResultOrder("USD", "RUB", "RSD")
    }

    private fun dragAbove(code: String, before: String) {
        val list = compose.onNodeWithTag("converter-list")
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("drag-$code").fetchSemanticsNode().boundsInRoot.center - viewport.topLeft
        val row = compose.onNodeWithTag("target-$before").fetchSemanticsNode().boundsInRoot
        val stop = Offset(start.x, row.center.y - viewport.top - 8f)
        list.performTouchInput {
            down(start)
            advanceEventTime(650)
            moveTo(start)
            moveTo(stop)
            up()
        }
    }

    private fun waitForTargets(targets: List<String>) {
        compose.waitUntil(5000) { runBlocking { preferenceStore.values.first().targets == targets } }
    }

    @Test fun addsAndRemovesTargetsIndependentlyOfFavorites() {
        show()
        scrollConverterTo("Add currency")
        compose.onNodeWithText("Add currency").performClick()
        compose.onNodeWithText("Search currencies").performTextInput("Japanese")
        pick("JPY").performClick()
        compose.onNodeWithContentDescription("Add JPY to favorites").performClick()
        waitForText("Favorites")
        pick("JPY").assertIsSelected()
        compose.onNodeWithText("Done").performClick()
        waitForTag("result-JPY")
        compose.onNodeWithTag("result-JPY").assertTextEquals("17,500.00")
        compose.onNodeWithContentDescription("Options for JPY").performClick()
        compose.onNodeWithText("Remove currency").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("result-JPY").fetchSemanticsNodes().isEmpty() }
        scrollConverterTo("Add currency")
        compose.onNodeWithText("Add currency").performClick()
        compose.onNodeWithContentDescription("Remove JPY from favorites").assertIsDisplayed()
    }

    @Test fun opensHistoryAndShowsItsEmptyState() {
        show()
        compose.onNodeWithContentDescription("History").performClick()
        waitForText("Historical data unavailable")
        period("30D").assertIsSelected()
        period("7D").performClick().assertIsSelected()
    }

    @Test fun showsStoredHistoryAndCanSelectChartObservations() {
        show(history = listOf(point("2026-11-17", "2"), point("2026-11-18", "1.25")))
        compose.onNodeWithContentDescription("History").performClick()
        waitForText("Latest")
        compose.onNodeWithContentDescription("Exchange rate chart").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous observation").performClick()
        val displayed = SemanticsMatcher("selected point ends in its rate") { node ->
            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                .any { it.text.endsWith(" · 2") }
        }
        compose.onNode(displayed).assertIsDisplayed()
    }

    @Test fun showsUnsupportedPairInHistory() {
        show(historyLoader = { _, _ -> flowOf(HistoryState(loading = false, problem = HistoryProblem.UNSUPPORTED)) })
        compose.onNodeWithContentDescription("History").performClick()
        waitForText("Historical rates are not available for this currency pair.")
        compose.onNodeWithContentDescription("Exchange rate chart").assertDoesNotExist()
    }

    @Test fun keepsCachedHistoryVisibleOffline() {
        show(connected = false, history = listOf(point("2026-11-15", "1.2"), point("2026-11-17", "1.25")))
        compose.onNodeWithContentDescription("History").performClick()
        waitForText("Offline · showing saved history")
        compose.onNodeWithContentDescription("Exchange rate chart").assertIsDisplayed()
    }

    @Test fun changingPeriodPreventsAnOutdatedResultFromBeingDisplayed() {
        val initialStarted = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<Unit>()
        show(historyLoader = { query, _ ->
            flow {
                val thirtyDays = query.from == query.to.minusDays(29)
                if (thirtyDays) {
                    initialStarted.complete(Unit)
                    oldResult.await()
                }
                val value = if (thirtyDays) "8.75" else "1.25"
                emit(HistoryState(HistorySeries(query, clock.millis(), listOf(point("2026-11-18", value))), loading = false))
            }
        })
        compose.onNodeWithContentDescription("History").performClick()
        compose.waitUntil(5000) { initialStarted.isCompleted }
        period("7D").performClick()
        waitForText("1.25")
        oldResult.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("8.75").assertDoesNotExist()
        compose.onNodeWithText("1.25").assertIsDisplayed()
    }

    @Test fun swappingTwiceKeepsPeriodAndDoesNotChangeConverterSelection() {
        val observations = listOf(point("2021-11-18", "2"), point("2024-02-29", "5"), point("2026-11-18", "4"))
        show(historyLoader = { query, _ ->
            val series = HistorySeries(
                HistoryQuery.endingOn("EUR", "USD", HistoryPeriod.FIVE_YEARS, query.to),
                clock.millis(),
                observations,
            )
            flowOf(HistoryState(series.forQuery(query, slice = true), loading = false))
        })
        compose.onNodeWithContentDescription("History").performClick()
        period("5Y").performScrollTo().performClick().assertIsSelected()
        waitForText("1 EUR = 4 USD")
        compose.onNodeWithContentDescription("Exchange rate chart").assertIsDisplayed()
        compose.onNodeWithContentDescription("Swap currencies").performClick()
        waitForText("1 USD = 0.25 EUR")
        period("5Y").assertIsSelected()
        compose.onNodeWithText("-50%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Swap currencies").performClick()
        waitForText("1 EUR = 4 USD")
        period("5Y").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("source-picker").assertTextEquals("€", "EUR")
        assertResultOrder("USD", "RSD", "RUB")
    }

    @Test fun rapidDoubleSwapCannotDisplayAnOutdatedPair() {
        val reversedStarted = CompletableDeferred<Unit>()
        val reversedResult = CompletableDeferred<Unit>()
        show(historyLoader = { query, _ ->
            flow {
                if (query.source == "USD") {
                    reversedStarted.complete(Unit)
                    reversedResult.await()
                }
                val rate = if (query.source == "EUR") "4" else "0.25"
                emit(HistoryState(HistorySeries(query, clock.millis(), listOf(point("2026-11-18", rate))), loading = false))
            }
        })
        compose.onNodeWithContentDescription("History").performClick()
        waitForText("1 EUR = 4 USD")
        compose.onNodeWithContentDescription("Swap currencies").performClick()
        compose.waitUntil(5000) { reversedStarted.isCompleted }
        compose.onNodeWithContentDescription("Swap currencies").performClick()
        waitForText("1 EUR = 4 USD")
        reversedResult.complete(Unit)
        compose.onNodeWithText("1 USD = 0.25 EUR").assertDoesNotExist()
    }

    @Test fun serbianLatinLocalizesScreensCurrencyNamesAndNumbersInDarkAndLight() {
        show(history = listOf(point("2026-11-17", "1.2"), point("2026-11-18", "1.25")))
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Srpski").performClick()
        waitForText("Podešavanja")
        compose.onNodeWithText("Srpski").assertIsSelected()
        compose.onNodeWithText("Tamna").performClick()
        compose.onNodeWithContentDescription("Nazad").performClick()
        waitForText("Šaljete")
        compose.onNodeWithTag("result-RSD").assertTextEquals("12.500,00")
        compose.onNodeWithContentDescription("Obriši iznos").assertIsDisplayed()
        compose.onNodeWithText("evro", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Istorija").performClick()
        waitForText("1 EUR = 1,25 USD")
        compose.onNodeWithContentDescription("Zameni valute").assertIsDisplayed()
        compose.onNodeWithContentDescription("30 dana").assertIsSelected()
        compose.onNodeWithContentDescription("Nazad").performClick()
        compose.onNodeWithContentDescription("Podešavanja").performClick()
        compose.onNodeWithText("Svetla").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Svetla").fetchSemanticsNodes()
                .any { it.config.getOrElse(SemanticsProperties.Selected) { false } }
        }
        compose.onNodeWithText("English").performClick()
        waitForText("Settings")
    }

    private fun period(label: String) = compose.onNodeWithTag("period-$label")

    private fun point(day: String, value: String) = HistoryPoint(LocalDate.parse(day), BigDecimal(value))

    @Test fun settingsShowsAnExplicitlyDisabledCheckWhenNoFeedIsConfigured() {
        show()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Check for updates").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("This build has no update source. The project README explains how to configure one.")
            .assertIsDisplayed()
    }

    @Test fun opensSettingsAndSwitchesThemeAndLanguage() {
        show()
        compose.onNodeWithContentDescription("Settings").performClick()
        waitForText("Appearance")
        compose.onNodeWithText("System").assertIsSelected()
        compose.onNodeWithText("Dark").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Dark").fetchSemanticsNodes().any {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Selected) { false }
            }
        }
        compose.onNodeWithText("Русский").performClick()
        waitForText("Внешний вид")
        compose.onNodeWithText("Тёмная").assertIsSelected()
        compose.onNodeWithText("Русский").assertIsSelected()
        compose.onNodeWithContentDescription("Назад").performClick()
        waitForText("Вы отправляете")
        compose.onNodeWithTag("result-USD").assertTextEquals("125,00")
    }

    @Test fun showsOfflineStatusWhileConvertingWithCachedRates() {
        show(connected = false)
        scrollConverterTo("Offline · using cached rates")
        compose.onNodeWithText("Offline · using cached rates").assertIsDisplayed()
        compose.onNodeWithText("Rate updated").assertIsDisplayed()
    }

    @Test fun firstOfflineLaunchExplainsDownloadAndOffersRetry() {
        show(initial = null, connected = false)
        scrollConverterTo("Download exchange rates")
        compose.onNodeWithText("Download exchange rates").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    private fun show(
        initial: CachedRates? = cached,
        connected: Boolean = true,
        history: List<HistoryPoint> = emptyList(),
        historyLoader: ((HistoryQuery, Boolean) -> Flow<HistoryState>)? = null,
        prefs: UserPreferences = UserPreferences(),
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "ui-${UUID.randomUUID()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + dataJob),
            produceFile = { file },
        )
        preferenceStore = UserPreferencesStore(dataStore)
        if (prefs != UserPreferences()) runBlocking { preferenceStore.update { prefs } }
        val repository = object : ExchangeRateRepository {
            override val latest = flowOf(initial)
            override val status = MutableStateFlow(RefreshStatus(problem = if (connected) null else RateProblem.OFFLINE))
            override suspend fun refresh(force: Boolean) {
                conversionRequests.incrementAndGet()
            }
        }
        val historyRepository = object : HistoricalRatesRepository {
            override fun currencies() = flowOf(listOf("KPW"))
            override fun load(query: HistoryQuery, force: Boolean) = historyLoader?.invoke(query, force)
                ?: flowOf(HistoryState(HistorySeries(query, clock.millis(), history), loading = false))
        }
        val factory = viewModelFactory {
            initializer {
                PocketFxViewModel(preferenceStore, repository, historyRepository, flowOf(connected), clock)
            }
        }
        val model = ViewModelProvider.create(models, factory)[PocketFxViewModel::class]
        compose.setContent { PocketFxApp(model) }
        waitForTag("source-amount")
    }

    private fun pick(code: String) = compose.onNodeWithTag("pick-$code")

    private fun waitForText(text: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollConverterTo(text: String) {
        compose.onNodeWithTag("converter-list").performScrollToNode(hasText(text))
    }
}
