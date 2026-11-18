package com.pocketfx.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.reflect.TypeToken
import com.pocketfx.data.remote.StrictJson
import com.pocketfx.domain.AppLanguage
import com.pocketfx.domain.ThemeMode
import com.pocketfx.domain.UserPreferences
import com.pocketfx.domain.isCurrencyCode
import com.pocketfx.domain.normalizeAmount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class UserPreferencesStore(private val store: DataStore<Preferences>) {
    val values: Flow<UserPreferences> = store.data.map { it.toUserPreferences() }.distinctUntilChanged()

    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        store.edit { stored ->
            val current = stored.toUserPreferences()
            val value = transform(current)
            stored[Source] = value.sourceCurrency
            stored[Amount] = value.sourceAmount
            stored[Targets] = StrictJson.toJson(value.targets)
            stored[Favorites] = StrictJson.toJson(value.favorites)
            stored[Theme] = value.theme.name
            stored[Language] = value.language.name
        }
    }

    private fun Preferences.toUserPreferences(): UserPreferences {
        val source = get(Source)?.takeIf(::isCurrencyCode) ?: Defaults.sourceCurrency
        return UserPreferences(
            sourceCurrency = source,
            sourceAmount = get(Amount)?.let(::normalizeAmount) ?: Defaults.sourceAmount,
            targets = get(Targets).asCurrencyList(Defaults.targets).filterNot { it == source },
            favorites = get(Favorites).asCurrencyList(Defaults.favorites),
            theme = ThemeMode.entries.find { it.name == get(Theme) } ?: Defaults.theme,
            language = AppLanguage.entries.find { it.name == get(Language) } ?: Defaults.language,
        )
    }

    private fun String?.asCurrencyList(default: List<String>): List<String> {
        return this?.let {
            runCatching {
                StrictJson.fromJson(it, CurrencyList)?.filterNotNull()?.filter(::isCurrencyCode)?.distinct()
            }.getOrNull()
        } ?: default
    }

    private companion object {
        val Defaults = UserPreferences()
        val CurrencyList = object : TypeToken<List<String?>>() {}
        val Source = stringPreferencesKey("source_currency")
        val Amount = stringPreferencesKey("source_amount")
        val Targets = stringPreferencesKey("targets")
        val Favorites = stringPreferencesKey("favorites")
        val Theme = stringPreferencesKey("theme")
        val Language = stringPreferencesKey("language")
    }
}
