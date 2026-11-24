package com.pocketfx.ui.converter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.pocketfx.R
import com.pocketfx.domain.ResolvedExchangeRate
import com.pocketfx.domain.parseCustomRate
import com.pocketfx.ui.components.LocalAppLocale
import com.pocketfx.ui.components.MutedText
import com.pocketfx.ui.components.asRate
import com.pocketfx.ui.components.asRateInput

@Composable
internal fun ExchangeRateEditor(
    source: String,
    target: String,
    rate: ResolvedExchangeRate?,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val locale = LocalAppLocale.current
    val title = stringResource(R.string.edit_exchange_rate)
    val rateLabel = stringResource(R.string.exchange_rate)
    val invalidRate = stringResource(R.string.invalid_exchange_rate)
    val resetLabel = stringResource(R.string.reset_api_rate)
    val cancelLabel = stringResource(R.string.cancel)
    val saveLabel = stringResource(R.string.save_rate)
    val apiReference = rate?.apiRate?.let {
        stringResource(R.string.api_rate_reference, it.asRate(locale, 12), target)
    } ?: stringResource(R.string.api_rate_unavailable)
    val fieldFocus = remember { FocusRequester() }
    var input by rememberSaveable(source, target, stateSaver = TextFieldValue.Saver) {
        val initial = rate?.effectiveRate?.asRateInput(locale).orEmpty()
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val parsed = remember(input.text) { parseCustomRate(input.text) }
    val invalid = parsed == null && input.text.isNotEmpty()
    val save = {
        if (parsed != null) {
            // Saving an unchanged reciprocal keeps the original, exact value.
            if (rate?.customRate?.compareTo(parsed) != 0) onSave(input.text)
            onClose()
        }
    }
    LaunchedEffect(fieldFocus) { fieldFocus.requestFocus() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus).testTag("custom-rate-input"),
                    label = { Text(rateLabel) },
                    prefix = { Text("1 $source =") },
                    suffix = { Text(target) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text(invalidRate) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                MutedText(apiReference, Modifier.testTag("api-rate-reference"))
                if (rate?.isCustom == true) {
                    TextButton(onClick = {
                        onReset()
                        onClose()
                    }) {
                        Text(resetLabel)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(cancelLabel) } },
        confirmButton = {
            TextButton(onClick = save, enabled = parsed != null) { Text(saveLabel) }
        },
    )
}
