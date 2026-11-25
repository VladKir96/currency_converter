package com.pocketfx.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal fun rememberNumericTextFormat(): NumericTextFormat {
    val locale = LocalAppLocale.current
    return remember(locale) { NumericTextFormat(locale) }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun NumericTextFormat.numericInput(accepts: (String) -> Boolean): InputTransformation =
    InputTransformation {
        if (changes.changeCount == 0) return@InputTransformation
        // Right-to-left edits keep the offsets of earlier changes stable.
        for (index in changes.changeCount - 1 downTo 0) {
            val range = changes.getRange(index)
            val inserted = asCharSequence().subSequence(range.min, range.max).toString()
            val normalized = parseEdit(inserted)
            if (normalized == null) {
                revertAllChanges()
                return@InputTransformation
            }
            if (inserted != normalized) replace(range.min, range.max, normalized)
        }
        if (!accepts(asCharSequence().toString())) revertAllChanges()
    }

internal fun NumericTextFormat.groupedOutput(): OutputTransformation = OutputTransformation {
    val text = asCharSequence()
    val decimal = text.indexOf('.')
    val offsets = groupingOffsets(text)
    if (decimal >= 0 && decimalSeparator != '.') replace(decimal, decimal + 1, decimalSeparator.toString())
    for (offset in offsets) replace(offset, offset, groupingSeparator.toString())
}
