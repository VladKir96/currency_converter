package com.pocketfx.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfx.data.preferences.UserPreferencesStore
import com.pocketfx.data.repository.ExchangeRateRepository
import com.pocketfx.data.repository.HistoricalRatesRepository
import com.pocketfx.domain.AppLanguage
import com.pocketfx.domain.CachedRates
import com.pocketfx.domain.HistoryQuery
import com.pocketfx.domain.ThemeMode
import com.pocketfx.domain.UserPreferences
import com.pocketfx.domain.isCurrencyCode
import com.pocketfx.domain.normalizeAmount
import com.pocketfx.domain.withSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock

internal data class RateCacheState(
    val data: CachedRates? = null,
    val loaded: Boolean = false,
    val failed: Boolean = false,
)

internal class PocketFxViewModel(
    private val preferenceStore: UserPreferencesStore,
    private val rates: ExchangeRateRepository,
    private val historicalRates: HistoricalRatesRepository,
    connected: Flow<Boolean>,
    private val clock: Clock,
) : ViewModel() {
    private val preferenceState = MutableStateFlow<UserPreferences?>(null)
    private val cacheState = MutableStateFlow(RateCacheState())
    private val preferenceReadError = MutableStateFlow(false)
    private val preferenceWriteError = MutableStateFlow(false)
    private val preferenceChanges = Channel<(UserPreferences) -> UserPreferences>(Channel.UNLIMITED)

    val preferences = preferenceState.asStateFlow()
    val cache = cacheState.asStateFlow()
    val settingsReadFailed = preferenceReadError.asStateFlow()
    val settingsWriteFailed = preferenceWriteError.asStateFlow()
    val refreshStatus = rates.status
    val online = connected.catch { emit(true) }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val time = flow {
        while (true) {
            emit(clock.millis())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), clock.millis())
    @OptIn(ExperimentalCoroutinesApi::class)
    fun history(query: HistoryQuery, refreshes: Flow<Unit>) = refreshes.map { true }
        .onStart { emit(false) }
        .flatMapLatest { force -> historicalRates.load(query, force) }

    fun historyCurrencies() = historicalRates.currencies()

    init {
        viewModelScope.launch {
            observeWithRetry({ preferenceStore.values }, onError = {
                preferenceReadError.value = true
                if (preferenceState.value == null) preferenceState.value = UserPreferences()
            }) {
                preferenceState.value = it
                preferenceReadError.value = false
            }
        }
        viewModelScope.launch {
            observeWithRetry({ rates.latest }, onError = {
                cacheState.value = cacheState.value.copy(loaded = true, failed = true)
            }) {
                cacheState.value = RateCacheState(it, loaded = true)
            }
        }
        viewModelScope.launch {
            for (change in preferenceChanges) {
                try {
                    preferenceStore.update(change)
                    preferenceWriteError.value = false
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    preferenceWriteError.value = true
                }
            }
        }
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch { rates.refresh(force) }
    }

    fun setAmount(input: String) {
        val amount = normalizeAmount(input) ?: return
        edit { it.copy(sourceAmount = amount) }
    }

    fun setSource(code: String) {
        edit { it.withSource(code) }
    }

    fun toggleTarget(code: String) {
        if (!isCurrencyCode(code)) return
        edit { prefs ->
            when {
                code == prefs.sourceCurrency -> prefs
                code in prefs.targets -> prefs.copy(targets = prefs.targets - code)
                else -> prefs.copy(targets = prefs.targets + code)
            }
        }
    }

    fun removeTarget(code: String) = edit { it.copy(targets = it.targets - code) }

    fun moveTarget(code: String, offset: Int) = edit { prefs ->
        val from = prefs.targets.indexOf(code)
        val to = from + offset
        if (from < 0 || to !in prefs.targets.indices) prefs
        else prefs.copy(targets = prefs.targets.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun toggleFavorite(code: String) {
        if (!isCurrencyCode(code)) return
        edit { prefs ->
            prefs.copy(favorites = if (code in prefs.favorites) prefs.favorites - code else prefs.favorites + code)
        }
    }

    fun setTheme(theme: ThemeMode) = edit { it.copy(theme = theme) }
    fun setLanguage(language: AppLanguage) = edit { it.copy(language = language) }

    private fun edit(transform: (UserPreferences) -> UserPreferences) {
        preferenceChanges.trySend(transform)
    }

    private suspend fun <T> observeWithRetry(
        observe: () -> Flow<T>,
        onError: suspend () -> Unit,
        onValue: suspend (T) -> Unit,
    ) {
        while (true) {
            try {
                observe().collect { onValue(it) }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onError()
                delay(5_000)
            }
        }
    }
}
