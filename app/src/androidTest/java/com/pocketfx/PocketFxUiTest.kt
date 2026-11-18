package com.pocketfx

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import com.pocketfx.data.repository.RateProblem
import com.pocketfx.data.repository.RefreshStatus
import com.pocketfx.domain.CachedRates
import com.pocketfx.domain.RateSnapshot
import com.pocketfx.ui.PocketFxApp
import com.pocketfx.ui.PocketFxViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PocketFxUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val dataJob = Job()
    private val clock = Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var file: File
    private val rates = mapOf("USD" to "1", "EUR" to "0.8", "RSD" to "100", "RUB" to "92.1", "JPY" to "140")
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

    @Test fun selectingTargetAsSourceRemovesItsDuplicateAndUpdatesResults() {
        show()
        compose.onNodeWithTag("source-picker").performClick()
        pick("USD").performClick()
        waitForText("10,000.00")
        compose.onNodeWithTag("result-USD").assertDoesNotExist()
        compose.onNodeWithTag("result-RSD").assertTextEquals("10,000.00")
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

    @Test fun opensHistoryAndShowsItsHonestEmptyState() {
        show()
        compose.onNodeWithContentDescription("History").performClick()
        waitForText("History will build up over time")
        compose.onNodeWithText("30 days").assertIsSelected()
        compose.onNodeWithText("7 days").performClick().assertIsSelected()
    }

    @Test fun showsStoredHistoryAndCanSelectChartObservations() {
        val oneDay = Duration.ofDays(1).toMillis()
        val earlier = snapshot.copy(
            providerUpdatedAt = snapshot.providerUpdatedAt - oneDay,
            rates = rates + ("EUR" to BigDecimal("0.5")),
        )
        show(history = listOf(earlier, snapshot))
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
        history: List<RateSnapshot> = emptyList(),
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "ui-${UUID.randomUUID()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + dataJob),
            produceFile = { file },
        )
        val repository = object : ExchangeRateRepository {
            override val latest = flowOf(initial)
            override val status = MutableStateFlow(RefreshStatus(problem = if (connected) null else RateProblem.OFFLINE))
            override fun history(since: Long) = flowOf(history)
            override suspend fun refresh(force: Boolean) = Unit
        }
        val factory = viewModelFactory {
            initializer { PocketFxViewModel(UserPreferencesStore(dataStore), repository, flowOf(connected), clock) }
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
