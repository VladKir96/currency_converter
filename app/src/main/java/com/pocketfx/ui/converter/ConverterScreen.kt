package com.pocketfx.ui.converter

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketfx.R
import com.pocketfx.data.repository.RefreshStatus
import com.pocketfx.domain.DefaultCurrencies
import com.pocketfx.domain.UserPreferences
import com.pocketfx.domain.convert
import com.pocketfx.domain.normalizeAmount
import com.pocketfx.domain.parseAmount
import com.pocketfx.ui.PocketFxViewModel
import com.pocketfx.ui.RateCacheState
import com.pocketfx.ui.calculator.CalculatorBottomSheet
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
    val calculator by model.calculator.collectAsStateWithLifecycle()
    val amount = remember(input.text) { parseAmount(input.text) }
    val refresh = { model.refresh(force = true) }
    val focus = LocalFocusManager.current

    val list = rememberLazyListState()
    val reorder = remember(list) { TargetDragState(list) }
    val haptics = LocalHapticFeedback.current
    val savedTargets by rememberUpdatedState(prefs.targets)
    val displayed = reorder.order ?: prefs.targets
    val writeFailed by model.settingsWriteFailed.collectAsStateWithLifecycle()
    val edgeSize = with(LocalDensity.current) { 76.dp.toPx() }

    LaunchedEffect(prefs.targets, writeFailed) { reorder.reconcile(prefs.targets, writeFailed) }
    LaunchedEffect(reorder.draggedCode, edgeSize) {
        if (reorder.draggedCode == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (reorder.draggedCode != null) {
            val frame = withFrameNanos { it }
            val elapsed = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            previousFrame = frame
            val delta = reorder.autoScrollSpeed(edgeSize) * elapsed
            if (delta != 0f) {
                list.scrollBy(delta)
                reorder.updateOrder()
            }
        }
    }

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
                Modifier.widthIn(max = ContentWidth).fillMaxWidth().testTag("converter-list")
                    .targetReorderGestures(
                        reorder,
                        savedTargets = { savedTargets },
                        onStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDrop = model::reorderTargets,
                    ),
                state = list,
                contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 12.dp),
            ) {
                item("source") {
                    Box(Modifier.padding(bottom = 14.dp)) {
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
                            onCalculator = {
                                focus.clearFocus()
                                model.openCalculator(input.text)
                            },
                        )
                    }
                }
                item("results_heading") {
                    SectionHeading(stringResource(R.string.you_receive), Modifier.padding(bottom = 14.dp))
                }
                if (displayed.isEmpty()) {
                    item("empty_targets") {
                        Panel { MutedText(stringResource(R.string.no_targets)) }
                    }
                }
                itemsIndexed(displayed, key = { _, code -> targetKey(code) }) { index, code ->
                    val dragging = reorder.draggedCode == code
                    val first = index == 0
                    val last = index == displayed.lastIndex
                    val placement = if (dragging) {
                        Modifier.zIndex(1f).graphicsLayer {
                            translationY = reorder.translationY()
                            scaleX = 1.015f
                            scaleY = 1.015f
                        }
                    } else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                    val shape = if (dragging) RoundedCornerShape(16.dp) else RoundedCornerShape(
                        topStart = if (first) 22.dp else 0.dp,
                        topEnd = if (first) 22.dp else 0.dp,
                        bottomStart = if (last) 22.dp else 0.dp,
                        bottomEnd = if (last) 22.dp else 0.dp,
                    )
                    val currentRate = convert(BigDecimal.ONE, prefs.sourceCurrency, code, rates)
                    val result = amount?.let { convert(it, prefs.sourceCurrency, code, rates) }
                    Surface(
                        modifier = placement.fillMaxWidth().testTag(targetKey(code)),
                        shape = shape,
                        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                        shadowElevation = if (dragging) 5.dp else 0.dp,
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp)) {
                            if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ResultRow(
                                info = currencyInfo(code, locale),
                                source = prefs.sourceCurrency,
                                value = result,
                                rate = currentRate,
                                showRateUnavailable = cache.data != null,
                                canDrag = displayed.size > 1,
                                canMoveUp = !first,
                                canMoveDown = !last,
                                onMove = {
                                    reorder.clearPreview()
                                    model.moveTarget(code, it)
                                },
                                onRemove = {
                                    reorder.clearPreview()
                                    model.removeTarget(code)
                                },
                            )
                        }
                    }
                }
                item("add") {
                    OutlinedButton(
                        onClick = {
                            focus.clearFocus()
                            picker = Picker.TARGETS
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.add_currency))
                    }
                }
                if (cache.data == null) {
                    item("first_download") {
                        Panel(Modifier.padding(top = 14.dp)) {
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
                item("status") {
                    Box(Modifier.padding(top = 14.dp)) {
                        RateStatusPanel(cache.data, status, cache.failed, online, now, refresh)
                    }
                }
                item("attribution") { RateAttribution(Modifier.padding(top = 14.dp)) }
            }
        }
    }

    calculator?.let { state ->
        CalculatorBottomSheet(
            state, model::calculatorKey,
            onApply = { model.applyCalculator()?.let { input = TextFieldValue(it, TextRange(it.length)) } },
            onClose = model::closeCalculator,
        )
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
    onCalculator: () -> Unit,
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
                    IconButton(onClick = onCalculator) {
                        Icon(painterResource(R.drawable.ic_calculator), stringResource(R.string.calculator))
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
    canDrag: Boolean,
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
        if (canDrag) {
            Icon(
                painterResource(R.drawable.ic_drag_handle),
                stringResource(R.string.reorder_currency, info.code),
                Modifier.size(18.dp).testTag("drag-${info.code}"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        display,
                        copyModifier.testTag("result-${info.code}"),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
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
