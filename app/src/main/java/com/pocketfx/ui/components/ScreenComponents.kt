package com.pocketfx.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketfx.R
import com.pocketfx.data.repository.RateProblem
import com.pocketfx.data.repository.RefreshStatus
import com.pocketfx.domain.CachedRates
import com.pocketfx.domain.RateTtl

internal val ContentWidth = 592.dp
private val PanelShape = RoundedCornerShape(22.dp)

@Composable
internal fun PocketTopBar(
    @StringRes title: Int,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.widthIn(max = ContentWidth).fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack == null) {
                    Surface(
                        modifier = Modifier.padding(horizontal = 6.dp).size(36.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(11.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_swap), contentDescription = null, Modifier.size(20.dp))
                        }
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
                Text(
                    stringResource(title),
                    Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                actions()
            }
        }
    }
}

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    highlight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PanelShape,
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun ErrorText(@StringRes message: Int, modifier: Modifier = Modifier) {
    Text(stringResource(message), modifier, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun CurrencyMark(symbol: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(min = 40.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(Modifier.padding(horizontal = 7.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
internal fun RateStatusPanel(
    cache: CachedRates?,
    status: RefreshStatus,
    cacheFailed: Boolean,
    online: Boolean,
    now: Long,
    onRefresh: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val locale = LocalAppLocale.current
    val mode = when {
        status.refreshing -> R.string.refreshing
        !online && cache != null -> R.string.offline_cached
        status.problem == RateProblem.OFFLINE && cache != null -> R.string.offline_cached
        (status.problem != null || cacheFailed) && cache != null -> R.string.cached_rates
        cache != null && (now - cache.lastCheckedAt >= RateTtl.toMillis() ||
            cache.nextProviderUpdate?.let { it <= now } == true) -> R.string.stale_rates
        cache != null -> R.string.rates_current
        else -> null
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (mode != null) {
                    Text(stringResource(mode), color = palette.primary, style = MaterialTheme.typography.labelLarge)
                }
                StatusTimestamp(R.string.last_checked, cache?.lastCheckedAt?.let { formatTimestamp(it, locale) })
                StatusTimestamp(R.string.rate_updated, cache?.snapshot?.providerUpdatedAt?.let { formatTimestamp(it, locale) })
            }
            IconButton(onClick = onRefresh, enabled = !status.refreshing) {
                if (status.refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, stringResource(R.string.refresh), tint = palette.primary)
                }
            }
        }
        val problem = if (cacheFailed) RateProblem.STORAGE else status.problem
        if (problem != null) ErrorText(problem.label, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StatusTimestamp(@StringRes label: Int, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MutedText(stringResource(label), Modifier.weight(1f))
        Text(
            value ?: stringResource(R.string.unknown),
            Modifier.weight(1.15f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val AttributionUrl = "https://www.exchangerate-api.com"

@Composable
internal fun RateAttribution(modifier: Modifier = Modifier) {
    val handler = LocalUriHandler.current
    val label = stringResource(R.string.attribution)
    val unavailable = stringResource(R.string.error_network)
    val context = LocalContext.current
    TextButton(
        onClick = {
            runCatching { handler.openUri(AttributionUrl) }.onFailure {
                android.widget.Toast.makeText(context, unavailable, android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

internal val RateProblem.label: Int
    get() = when (this) {
        RateProblem.OFFLINE -> R.string.error_offline
        RateProblem.TIMEOUT -> R.string.error_timeout
        RateProblem.RATE_LIMIT -> R.string.error_rate_limit
        RateProblem.SERVER -> R.string.error_server
        RateProblem.RESPONSE -> R.string.error_response
        RateProblem.NETWORK -> R.string.error_network
        RateProblem.STORAGE -> R.string.error_storage
    }
