package com.pocketfx

import android.app.Application
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.pocketfx.data.local.RateDatabase
import com.pocketfx.data.local.RoomRateStore
import com.pocketfx.data.preferences.UserPreferencesStore
import com.pocketfx.data.remote.exchangeRatesApi
import com.pocketfx.data.remote.networkConnected
import com.pocketfx.data.repository.OpenExchangeRateRepository
import com.pocketfx.ui.PocketFxViewModel
import java.time.Clock

private val Application.preferences by preferencesDataStore(
    name = "pocket_fx_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class PocketFxApplication : Application() {
    private val database by lazy {
        Room.databaseBuilder(this, RateDatabase::class.java, "pocket-fx-rates.db").build()
    }
    private val clock = Clock.systemUTC()
    private val rates by lazy { OpenExchangeRateRepository(exchangeRatesApi(), RoomRateStore(database), clock) }

    internal fun createViewModel() =
        PocketFxViewModel(UserPreferencesStore(preferences), rates, networkConnected(this), clock)
}
