package com.pocketfx.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketfx.R
import com.pocketfx.data.repository.HistoryProblem
import com.pocketfx.data.repository.HistoryState
import com.pocketfx.domain.DefaultCurrencies
import com.pocketfx.domain.HistoryPeriods
import com.pocketfx.domain.HistoryQuery
import com.pocketfx.domain.HistoryStats
import com.pocketfx.domain.UserPreferences
import com.pocketfx.domain.historyStats
import com.pocketfx.ui.PocketFxViewModel
import com.pocketfx.ui.components.ContentWidth
import com.pocketfx.ui.components.CurrencyPicker
import com.pocketfx.ui.components.ErrorText
import com.pocketfx.ui.components.LocalAppLocale
import com.pocketfx.ui.components.MutedText
import com.pocketfx.ui.components.Panel
import com.pocketfx.ui.components.PocketTopBar
import com.pocketfx.ui.components.RateAttribution
import com.pocketfx.ui.components.SectionHeading
import com.pocketfx.ui.components.asPercent
import com.pocketfx.ui.components.asRate
import com.pocketfx.ui.components.formatDay
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant
import java.time.ZoneOffset

private enum class PairSide { SOURCE, TARGET }

@Composable
internal fun HistoryScreen(
    prefs: UserPreferences,
    model: PocketFxViewModel,
    currentCodes: Set<String>,
    now: Long,
    online: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val locale = LocalAppLocale.current
    val catalogFlow = remember(model) { model.historyCurrencies() }
    val catalog by catalogFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val codes = remember(currentCodes, catalog, prefs) {
        (DefaultCurrencies + currentCodes + catalog + prefs.sourceCurrency + prefs.targets + prefs.favorites).distinct()
    }
    var source by rememberSaveable { mutableStateOf(prefs.sourceCurrency) }
    var target by rememberSaveable {
        mutableStateOf(prefs.targets.firstOrNull { it != prefs.sourceCurrency } ?: codes.first { it != prefs.sourceCurrency })
    }
    var days by rememberSaveable { mutableIntStateOf(30) }
    var picker by rememberSaveable { mutableStateOf<PairSide?>(null) }
    val today = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate()
    val query = remember(source, target, days, today) { HistoryQuery.endingOn(source, target, days, today) }
    val refreshes = remember(query) { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val refresh: () -> Unit = { refreshes.tryEmit(Unit) }
    val history = key(query) {
        val flow = remember(model) { model.history(query, refreshes) }
        val state by flow.collectAsStateWithLifecycle(initialValue = HistoryState())
        state
    }
    val points = history.series?.points.orEmpty()
    val stats = remember(points) { historyStats(points) }
    val latest = stats?.latest ?: points.singleOrNull()?.rate

    Scaffold(
        topBar = {
            PocketTopBar(R.string.history, onBack) {
                IconButton(onClick = refresh, enabled = !history.loading) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.history_refresh))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item("pair") {
                    Panel {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PairButton(R.string.source_currency, source, Modifier.weight(1f)) { picker = PairSide.SOURCE }
                            PairButton(R.string.target_currency, target, Modifier.weight(1f)) { picker = PairSide.TARGET }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            for (period in HistoryPeriods) {
                                FilterChip(
                                    selected = days == period,
                                    onClick = { days = period },
                                    label = { Text(pluralStringResource(R.plurals.period_days, period, period)) },
                                )
                            }
                        }
                    }
                }
                if (latest != null) {
                    item("latest") {
                        Panel(highlight = true) {
                            Text(stringResource(R.string.history_latest), style = MaterialTheme.typography.labelLarge)
                            Text(
                                latest.asRate(locale),
                                Modifier.padding(vertical = 3.dp),
                                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(stringResource(R.string.pair_rate, source, latest.asRate(locale), target))
                        }
                    }
                }
                item("chart") {
                    Panel {
                        if (stats != null) {
                            RateChart(points, stats)
                            HistorySummary(stats, Modifier.padding(top = 8.dp))
                        }
                        HistoryFeedback(history, query, online, refresh)
                    }
                }
                item("attribution") {
                    RateAttribution(url = "https://frankfurter.dev/", labelRes = R.string.history_attribution)
                }
            }
        }
    }
    picker?.let { side ->
        val selectingSource = side == PairSide.SOURCE
        CurrencyPicker(
            title = if (selectingSource) R.string.choose_source else R.string.choose_target,
            codes = codes.filterNot { it == if (selectingSource) target else source },
            favorites = prefs.favorites,
            selectedCodes = listOf(if (selectingSource) source else target),
            onSelect = {
                if (selectingSource) source = it else target = it
                picker = null
            },
            onToggleFavorite = onToggleFavorite,
            onClose = { picker = null },
        )
    }
}

@Composable
private fun HistoryFeedback(
    state: HistoryState,
    query: HistoryQuery,
    online: Boolean,
    onRetry: () -> Unit,
) {
    val series = state.series
    val locale = LocalAppLocale.current
    val hasChart = (series?.points?.size ?: 0) >= 2
    val offline = !online || state.problem == HistoryProblem.OFFLINE
    if (series != null && series.points.isNotEmpty()) {
        if (offline) {
            MutedText(stringResource(R.string.history_offline_saved), Modifier.padding(top = if (hasChart) 10.dp else 0.dp))
        }
        if (series.query != query) {
            MutedText(
                stringResource(R.string.history_cached_range, formatDay(series.query.from, locale), formatDay(series.query.to, locale)),
                Modifier.padding(top = 4.dp),
            )
        }
    }

    val statusPadding = Modifier.padding(top = if (hasChart) 10.dp else 0.dp)
    when {
        state.loading -> Row(
            modifier = statusPadding,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            MutedText(stringResource(R.string.history_loading))
        }
        state.problem == HistoryProblem.UNSUPPORTED -> SectionHeading(stringResource(R.string.history_unavailable))
        state.problem == HistoryProblem.STORAGE -> ErrorText(R.string.history_save_failed, statusPadding)
        (state.problem == HistoryProblem.LOAD_FAILED && !offline) || (offline && series?.points.isNullOrEmpty()) -> {
            ErrorText(if (offline) R.string.history_offline else R.string.history_load_failed, statusPadding)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        !hasChart -> {
            SectionHeading(stringResource(R.string.history_empty), Modifier.padding(top = if (offline) 6.dp else 0.dp))
            MutedText(stringResource(R.string.history_empty_detail), Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun PairButton(label: Int, code: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier) {
        MutedText(stringResource(label))
        OutlinedButton(
            onClick,
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(code, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun HistorySummary(stats: HistoryStats, modifier: Modifier = Modifier) {
    val locale = LocalAppLocale.current
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Stat(R.string.history_min, stats.minimum.asRate(locale))
        Stat(R.string.history_max, stats.maximum.asRate(locale))
        Stat(R.string.history_change, stats.changePercent.asPercent(locale))
    }
}

@Composable
private fun Stat(label: Int, value: String) {
    Column {
        MutedText(stringResource(label))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}
