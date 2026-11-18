package com.pocketfx

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketfx.data.preferences.UserPreferencesStore
import com.pocketfx.domain.AppLanguage
import com.pocketfx.domain.ThemeMode
import com.pocketfx.domain.UserPreferences
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
            first.update { expected }
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

    private fun store(file: File, job: Job) =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
}
