package com.pocketfx.ui.calculator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketfx.R
import com.pocketfx.domain.calculator.CalculatorError
import com.pocketfx.domain.calculator.CalculatorKey
import com.pocketfx.domain.calculator.CalculatorState
import com.pocketfx.domain.calculator.calculatorText
import com.pocketfx.ui.components.ContentWidth
import com.pocketfx.ui.components.SectionHeading
import com.pocketfx.ui.components.rememberNumericTextFormat

private val KeyRows = listOf(
    listOf(CalculatorKey.CLEAR, CalculatorKey.SIGN, CalculatorKey.DIVIDE, CalculatorKey.BACKSPACE),
    listOf(CalculatorKey.SEVEN, CalculatorKey.EIGHT, CalculatorKey.NINE, CalculatorKey.MULTIPLY),
    listOf(CalculatorKey.FOUR, CalculatorKey.FIVE, CalculatorKey.SIX, CalculatorKey.SUBTRACT),
    listOf(CalculatorKey.ONE, CalculatorKey.TWO, CalculatorKey.THREE, CalculatorKey.ADD),
    listOf(CalculatorKey.TRIPLE_ZERO, CalculatorKey.ZERO, CalculatorKey.DECIMAL, CalculatorKey.EQUALS),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalculatorBottomSheet(state: CalculatorState, onKey: (CalculatorKey) -> Unit, onApply: () -> Unit, onClose: () -> Unit) {
    val numbers = rememberNumericTextFormat()
    val keyLabels = KeyRows.flatten().associateWith { key -> key.label?.let { stringResource(it) } }
    val title = stringResource(R.string.calculator)
    val closeLabel = stringResource(R.string.calculator_close)
    val applyLabel = stringResource(R.string.calculator_apply)
    val unknown = stringResource(R.string.unknown)
    val error = state.error?.let { stringResource(it.label) }
    val haptics = LocalHapticFeedback.current
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = ContentWidth,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeading(title, Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, closeLabel) }
            }
            Text(
                state.expression(numbers::format),
                Modifier.fillMaxWidth().testTag("calculator-expression"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
            Text(
                state.result?.let { numbers.format(it.calculatorText()) } ?: unknown,
                Modifier.fillMaxWidth().testTag("calculator-result").semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            for (keys in KeyRows) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (key in keys) {
                        val label = keyLabels[key]
                        val symbol = if (key == CalculatorKey.DECIMAL) numbers.decimalSeparator.toString() else key.symbol
                        val action = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onKey(key)
                        }
                        val modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                            .testTag("calc-${key.name}")
                            .then(if (label == null) Modifier else Modifier.semantics { contentDescription = label })
                        val content: @Composable () -> Unit = {
                            if (key == CalculatorKey.BACKSPACE) {
                                Icon(painterResource(R.drawable.ic_backspace), contentDescription = null)
                            } else {
                                Text(
                                    symbol,
                                    if (label == null) Modifier else Modifier.clearAndSetSemantics {},
                                    color = if (key.isOperator) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (key == CalculatorKey.EQUALS) Button(action, modifier, contentPadding = PaddingValues(2.dp)) { content() }
                        else FilledTonalButton(action, modifier, contentPadding = PaddingValues(2.dp)) { content() }
                    }
                }
            }
            Button(onApply, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = state.amount != null) {
                Text(applyLabel)
            }
        }
    }
}

private val CalculatorError.label: Int
    get() = when (this) {
        CalculatorError.INCOMPLETE -> R.string.calculator_incomplete
        CalculatorError.DIVISION_BY_ZERO -> R.string.calculator_zero_division
        CalculatorError.TOO_LONG -> R.string.calculator_too_long
        CalculatorError.TOO_LARGE -> R.string.calculator_too_large
        CalculatorError.NEGATIVE_AMOUNT -> R.string.calculator_negative
        CalculatorError.AMOUNT_TOO_LARGE -> R.string.calculator_amount_limit
    }

private val CalculatorKey.label: Int?
    get() = when (this) {
        CalculatorKey.TRIPLE_ZERO -> R.string.calculator_triple_zero
        CalculatorKey.DECIMAL -> R.string.calculator_decimal
        CalculatorKey.ADD -> R.string.calculator_add
        CalculatorKey.SUBTRACT -> R.string.calculator_subtract
        CalculatorKey.MULTIPLY -> R.string.calculator_multiply
        CalculatorKey.DIVIDE -> R.string.calculator_divide
        CalculatorKey.SIGN -> R.string.calculator_sign
        CalculatorKey.CLEAR -> R.string.calculator_clear
        CalculatorKey.BACKSPACE -> R.string.calculator_delete
        CalculatorKey.EQUALS -> R.string.calculator_equals
        else -> null
    }
