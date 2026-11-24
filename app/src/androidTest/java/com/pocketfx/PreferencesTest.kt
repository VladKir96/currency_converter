package com.pocketfx

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketfx.data.preferences.CustomRatesStore
import com.pocketfx.data.preferences.UserPreferencesStore
import com.pocketfx.domain.AppLanguage
import com.pocketfx.domain.CurrencyPair
import com.pocketfx.domain.CustomExchangeRate
import com.pocketfx.domain.ThemeMode
import com.pocketfx.domain.UserPreferences
import com.pocketfx.domain.withSource
import com.pocketfx.domain.withTargetOrder
import com.pocketfx.ui.components.formatDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun selectionsPersistWithOrderAndEmptyInputAcrossDataStoreRecreation() = runBlocking {
        val file = File(context.cacheDir, "prefs-${UUID.randomUUID()}.preferences_pb")
        val expected = UserPreferences(
            sourceCurrency = "RUB",
            sourceAmount = "",
            targets = listOf("EUR", "RSD", "USD"),
            favorites = listOf("JPY", "RUB"),
            theme = ThemeMode.DARK,
            language = AppLanguage.RUSSIAN,
        )
        val writer = Job()
        val reader = Job()
        try {
            val first = UserPreferencesStore(store(file, writer))
            assertEquals(UserPreferences(), first.values.first())
            first.update { expected.copy(sourceCurrency = "USD", targets = listOf("EUR", "RSD", "RUB")) }
            first.update { it.withSource("RUB") }
            assertEquals(expected, first.values.first())
            writer.cancelAndJoin()
            assertEquals(expected, UserPreferencesStore(store(file, reader)).values.first())
        } finally {
            writer.cancelAndJoin()
            reader.cancelAndJoin()
            file.delete()
        }
        Unit
    }

    @Test fun invalidPreferencesFallBackAndTargetEntriesAreSanitized() = runBlocking {
        val file = File(context.cacheDir, "prefs-${UUID.randomUUID()}.preferences_pb")
        val job = Job()
        try {
            val datastore = store(file, job)
            datastore.edit {
                it[stringPreferencesKey("source_currency")] = "!"
                it[stringPreferencesKey("source_amount")] = "invalid"
                it[stringPreferencesKey("targets")] = """["USD","USD","EUR","INVALID",null,"RUB"]"""
                it[stringPreferencesKey("favorites")] = "["
                it[stringPreferencesKey("theme")] = "lost"
                it[stringPreferencesKey("language")] = "random"
            }
            assertEquals(UserPreferences(targets = listOf("USD", "RUB")), UserPreferencesStore(datastore).values.first())
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
        Unit
    }

    @Test fun serbianLatinAndDraggedOrderSurvivePreferenceStoreRecreation() = runBlocking {
        val file = File(context.cacheDir, "prefs-${UUID.randomUUID()}.preferences_pb")
        val writer = Job()
        val reader = Job()
        val order = listOf("RUB", "USD", "RSD")
        try {
            val first = UserPreferencesStore(store(file, writer))
            first.update { it.withTargetOrder(order).copy(language = AppLanguage.SERBIAN_LATIN) }
            writer.cancelAndJoin()
            val restored = UserPreferencesStore(store(file, reader)).values.first()
            assertEquals(order, restored.targets)
            assertEquals("sr-Latn-RS", restored.language.locale.toLanguageTag())
            val date = formatDay(LocalDate.of(2026, 11, 18), restored.language.locale, includeYear = true)
            org.junit.Assert.assertTrue(Regex("18\\. ?11\\. ?2026\\.?").matches(date))
        } finally {
            writer.cancelAndJoin()
            reader.cancelAndJoin()
            file.delete()
        }
        Unit
    }

    @Test fun customRatesSurviveReopeningDataStoreAlongsideExistingPreferences() = runBlocking {
        val file = File(context.cacheDir, "prefs-${UUID.randomUUID()}.preferences_pb")
        val initialScope = Job()
        val writer = Job()
        val reader = Job()
        val restoredScope = Job()
        val rsd = CustomExchangeRate("EUR", "RSD", BigDecimal("117.123456789123456789"))
        val rub = CustomExchangeRate("EUR", "RUB", BigDecimal("105.00"))
        val existing = UserPreferences(
            sourceAmount = "42.5",
            targets = listOf("RUB", "RSD"),
            favorites = listOf("JPY"),
            theme = ThemeMode.DARK,
            language = AppLanguage.SERBIAN_LATIN,
        )
        try {
            UserPreferencesStore(store(file, initialScope)).update { existing }
            initialScope.cancelAndJoin()

            val dataStore = store(file, writer)
            val rates = CustomRatesStore(dataStore)
            assertEquals(emptyMap<CurrencyPair, CustomExchangeRate>(), rates.values.first())
            rates.save(rsd)
            rates.save(rub)
            writer.cancelAndJoin()

            val reopened = store(file, reader)
            val reopenedRates = CustomRatesStore(reopened)
            assertEquals(existing, UserPreferencesStore(reopened).values.first())
            assertEquals(mapOf(rsd.pair to rsd, rub.pair to rub), reopenedRates.values.first())
            val reversed = CustomExchangeRate("RSD", "EUR", BigDecimal("0.0085"))
            reopenedRates.save(reversed)
            assertEquals(mapOf(rsd.pair to reversed, rub.pair to rub), reopenedRates.values.first())
            reopenedRates.reset(CurrencyPair.of("RSD", "EUR"))
            reader.cancelAndJoin()

            val restored = store(file, restoredScope)
            assertEquals(mapOf(rub.pair to rub), CustomRatesStore(restored).values.first())
            assertEquals(existing, UserPreferencesStore(restored).values.first())
        } finally {
            initialScope.cancelAndJoin()
            writer.cancelAndJoin()
            reader.cancelAndJoin()
            restoredScope.cancelAndJoin()
            file.delete()
        }
        Unit
    }

    private fun store(file: File, job: Job) =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
}
