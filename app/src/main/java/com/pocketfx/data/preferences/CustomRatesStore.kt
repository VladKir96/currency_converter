package com.pocketfx.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketfx.domain.CurrencyPair
import com.pocketfx.domain.CustomExchangeRate
import com.pocketfx.domain.isCurrencyCode
import com.pocketfx.domain.parseCustomRate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class CustomRatesStore(private val store: DataStore<Preferences>) {
    val values = store.data.map { preferences ->
        preferences.asMap().mapNotNull { (key, value) ->
            read(key.name, value as? String)?.let { it.pair to it }
        }.toMap()
    }.distinctUntilChanged()

    suspend fun save(rate: CustomExchangeRate) {
        store.edit { it[rate.pair.key()] = "${rate.source}:${rate.rate.toPlainString()}" }
    }

    suspend fun reset(pair: CurrencyPair) {
        store.edit { it.remove(pair.key()) }
    }

    private fun read(key: String, value: String?): CustomExchangeRate? {
        if (!key.startsWith(Prefix) || value == null) return null
        val currencies = key.removePrefix(Prefix)
        if (currencies.length != 7 || currencies[3] != '_') return null
        val first = currencies.substring(0, 3)
        val second = currencies.substring(4)
        if (!isCurrencyCode(first) || !isCurrencyCode(second) || first >= second) return null
        val source = value.substringBefore(':')
        val target = when (source) {
            first -> second
            second -> first
            else -> return null
        }
        val rate = parseCustomRate(value.substringAfter(':', "")) ?: return null
        return CustomExchangeRate(source, target, rate)
    }

    private fun CurrencyPair.key() = stringPreferencesKey("${Prefix}${first}_$second")

    private companion object {
        const val Prefix = "custom_rate_"
    }
}
