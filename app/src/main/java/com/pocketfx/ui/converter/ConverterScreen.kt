package com.pocketfx.ui.converter

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketfx.R
import com.pocketfx.data.repository.RefreshStatus
import com.pocketfx.domain.DefaultCurrencies
import com.pocketfx.domain.UserPreferences
import com.pocketfx.domain.convert
import com.pocketfx.domain.normalizeAmount
import com.pocketfx.domain.parseAmount
import com.pocketfx.ui.PocketFxViewModel
import com.pocketfx.ui.RateCacheState
import com.pocketfx.ui.components.ContentWidth
import com.pocketfx.ui.components.CurrencyInfo
import com.pocketfx.ui.components.CurrencyMark
import com.pocketfx.ui.components.CurrencyPicker
import com.pocketfx.ui.components.LocalAppLocale
import com.pocketfx.ui.components.MutedText
import com.pocketfx.ui.components.Panel
import com.pocketfx.ui.components.PocketTopBar
import com.pocketfx.ui.components.RateAttribution
import com.pocketfx.ui.components.RateStatusPanel
import com.pocketfx.ui.components.SectionHeading
import com.pocketfx.ui.components.asMoney
import com.pocketfx.ui.components.asRate
import com.pocketfx.ui.components.currencyInfo
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.DecimalFormatSymbols

private enum class Picker { SOURCE, TARGETS }

@Composable
internal fun ConverterScreen(
    prefs: UserPreferences,
    cache: RateCacheState,
    status: RefreshStatus,
    online: Boolean,
    now: Long,
    model: PocketFxViewModel,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val locale = LocalAppLocale.current
    val rates = cache.data?.snapshot?.rates.orEmpty()
    val codes = remember(rates, prefs) {
        if (rates.isEmpty()) (DefaultCurrencies + prefs.sourceCurrency + prefs.targets + prefs.favorites).distinct()
        else rates.keys.toList()
    }
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(prefs.sourceAmount))
    }
    val amount = remember(input.text) { parseAmount(input.text) }
    val refresh = { model.refresh(force = true) }
    val focus = LocalFocusManager.current

    Scaffold(
        topBar = {
            PocketTopBar(R.string.app_name) {
                IconButton(onClick = onHistory) {
                    Icon(painterResource(R.drawable.ic_history), stringResource(R.string.history))
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, stringResource(R.string.settings))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = ContentWidth).fillMaxWidth().testTag("converter-list"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 12.dp),
            ) {
                item("source") {
                    SourcePanel(
                        info = currencyInfo(prefs.sourceCurrency, locale),
                        value = input,
                        onValue = { edited ->
                            normalizeAmount(edited.text)?.let { canonical ->
                                val changed = input.text != canonical
                                input = edited.copy(text = canonical)
                                if (changed) model.setAmount(canonical)
                            }
                        },
                        onPick = {
                            focus.clearFocus()
                            picker = Picker.SOURCE
                        },
                    )
                }
                item("results_heading") { SectionHeading(stringResource(R.string.you_receive)) }
                item("targets") {
                    Panel(padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        if (prefs.targets.isEmpty()) {
                            MutedText(stringResource(R.string.no_targets), Modifier.padding(12.dp))
                        }
                        prefs.targets.forEachIndexed { index, code ->
                            androidx.compose.runtime.key(code) {
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                val currentRate = convert(BigDecimal.ONE, prefs.sourceCurrency, code, rates)
                                val result = amount?.let { convert(it, prefs.sourceCurrency, code, rates) }
                                ResultRow(
                                    info = currencyInfo(code, locale),
                                    source = prefs.sourceCurrency,
                                    value = result,
                                    rate = currentRate,
                                    showRateUnavailable = cache.data != null,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < prefs.targets.lastIndex,
                                    onMove = { model.moveTarget(code, it) },
                                    onRemove = { model.removeTarget(code) },
                                )
                            }
                        }
                    }
                }
                item("add") {
                    OutlinedButton(
                        onClick = {
                            focus.clearFocus()
                            picker = Picker.TARGETS
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.add_currency))
                    }
                }
                if (cache.data == null) {
                    item("first_download") {
                        Panel {
                            if (!cache.loaded || status.refreshing) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    MutedText(stringResource(if (status.refreshing) R.string.refreshing else R.string.loading))
                                }
                            } else {
                                SectionHeading(stringResource(R.string.first_download_title))
                                MutedText(stringResource(R.string.first_download_body), Modifier.padding(top = 6.dp))
                                Button(refresh, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.retry)) }
                            }
                        }
                    }
                }
                item("status") { RateStatusPanel(cache.data, status, cache.failed, online, now, refresh) }
                item("attribution") { RateAttribution() }
            }
        }
    }

    picker?.let { mode ->
        CurrencyPicker(
            title = if (mode == Picker.SOURCE) R.string.choose_source else R.string.manage_targets,
            codes = if (mode == Picker.SOURCE) codes else (codes + prefs.targets).filterNot { it == prefs.sourceCurrency },
            favorites = prefs.favorites,
            selectedCodes = if (mode == Picker.SOURCE) listOf(prefs.sourceCurrency) else prefs.targets,
            onSelect = { code ->
                if (mode == Picker.SOURCE) {
                    model.setSource(code)
                    picker = null
                } else model.toggleTarget(code)
            },
            onToggleFavorite = model::toggleFavorite,
            onClose = { picker = null },
            multiSelect = mode == Picker.TARGETS,
        )
    }
}

@Composable
private fun SourcePanel(
    info: CurrencyInfo,
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    onPick: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val locale = LocalAppLocale.current
    val inputLabel = stringResource(R.string.source_amount)
    val currencyLabel = stringResource(R.string.source_currency)
    val amountFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val symbols = remember(locale) { DecimalFormatSymbols.getInstance(locale) }
    val transformation = remember(symbols) {
        VisualTransformation { text ->
            TransformedText(AnnotatedString(text.text.replace('.', symbols.decimalSeparator)), OffsetMapping.Identity)
        }
    }
    Panel(highlight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.you_send),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(
                onClick = onPick,
                modifier = Modifier.semantics { contentDescription = currencyLabel }.testTag("source-picker"),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                CurrencyMark(info.symbol)
                Text(info.code, Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(19.dp))
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).focusRequester(amountFocus)
                .semantics { contentDescription = inputLabel }.testTag("source-amount"),
            textStyle = MaterialTheme.typography.displaySmall.copy(
                color = palette.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            singleLine = true,
            visualTransformation = transformation,
            cursorBrush = SolidColor(palette.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            decorationBox = { field ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { field() }
                    if (value.text.isNotEmpty()) {
                        IconButton(onClick = {
                            onValue(TextFieldValue(""))
                            amountFocus.requestFocus()
                            keyboard?.show()
                        }) {
                            Icon(Icons.Default.Close, stringResource(R.string.clear_amount))
                        }
                    }
                }
            },
        )
        Text(info.name, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResultRow(
    info: CurrencyInfo,
    source: String,
    value: BigDecimal?,
    rate: BigDecimal?,
    showRateUnavailable: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val locale = LocalAppLocale.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.copy_amount)
    val copied = stringResource(R.string.copied)
    val display = value?.asMoney(locale) ?: stringResource(R.string.unknown)
    val copy: () -> Unit = {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(info.code, display)))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        CurrencyMark(info.symbol)
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(info.code, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val copyModifier = if (value == null) Modifier else Modifier
                    .pointerInput(display, copyLabel) { detectTapGestures(onLongPress = { copy() }) }
                    .semantics {
                        onLongClick(copyLabel) {
                            copy()
                            true
                        }
                    }
                Text(
                    display,
                    Modifier.weight(1f).then(copyModifier).testTag("result-${info.code}"),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (rate != null) MutedText(stringResource(R.string.pair_rate, source, rate.asRate(locale), info.code))
            else if (showRateUnavailable) MutedText(stringResource(R.string.no_rate))
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.target_options, info.code))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.move_up)) },
                    onClick = {
                        menuOpen = false
                        onMove(-1)
                    },
                    leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                    enabled = canMoveUp,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.move_down)) },
                    onClick = {
                        menuOpen = false
                        onMove(1)
                    },
                    leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                    enabled = canMoveDown,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remove_currency)) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                    leadingIcon = { Icon(Icons.Default.Close, null) },
                )
            }
        }
    }
}
