package com.pocketfx

import android.app.Application
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.pocketfx.data.local.MigrateRates1To2
import com.pocketfx.data.local.RateDatabase
import com.pocketfx.data.local.RoomHistoryStore
import com.pocketfx.data.local.RoomRateStore
import com.pocketfx.data.preferences.UserPreferencesStore
import com.pocketfx.data.remote.exchangeRatesApi
import com.pocketfx.data.remote.frankfurterApi
import com.pocketfx.data.remote.networkConnected
import com.pocketfx.data.repository.FrankfurterHistoryRepository
import com.pocketfx.data.repository.OpenExchangeRateRepository
import com.pocketfx.data.update.ApkInstaller
import com.pocketfx.data.update.DataStoreUpdateChecks
import com.pocketfx.data.update.UpdateHttp
import com.pocketfx.data.update.UpdateRepository
import com.pocketfx.data.update.UpdateSource
import com.pocketfx.ui.PocketFxViewModel
import com.pocketfx.ui.update.UpdateViewModel
import java.time.Clock

private val Application.preferences by preferencesDataStore(
    name = "pocket_fx_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val Application.updateChecks by preferencesDataStore(
    name = "pocket_fx_updates",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class PocketFxApplication : Application() {
    private val database by lazy {
        Room.databaseBuilder(this, RateDatabase::class.java, "pocket-fx-rates.db")
            .addMigrations(MigrateRates1To2)
            .build()
    }
    private val clock = Clock.systemUTC()
    private val rates by lazy { OpenExchangeRateRepository(exchangeRatesApi(), RoomRateStore(database), clock) }

    private val history by lazy { FrankfurterHistoryRepository(frankfurterApi(), RoomHistoryStore(database.history()), clock) }

    private val updateRepository by lazy {
        val source = UpdateSource(BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.UPDATE_APK_ORIGIN)
        val platform = ApkInstaller(this)
        UpdateRepository(
            platform.installed(), source, UpdateHttp(source), DataStoreUpdateChecks(updateChecks),
            cacheDir.resolve("app-updates"), platform::inspect, clock,
        )
    }

    internal fun createUpdateViewModel() = UpdateViewModel(updateRepository)

    internal fun createViewModel() =
        PocketFxViewModel(UserPreferencesStore(preferences), rates, history, networkConnected(this), clock)
}
