package com.pocketfx.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketfx.R
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CurrencyPicker(
    title: Int,
    codes: List<String>,
    favorites: List<String>,
    selectedCodes: List<String>,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onClose: () -> Unit,
    multiSelect: Boolean = false,
) {
    val locale = LocalAppLocale.current
    var query by rememberSaveable { mutableStateOf("") }
    val currencies = remember(codes, locale) {
        codes.distinct().sorted().map { currencyInfo(it, locale) }
    }
    val matches = remember(currencies, query, locale) {
        val needle = query.trim()
        val localizedNeedle = needle.lowercase(locale)
        currencies.filter {
            it.code.contains(needle.uppercase(Locale.ROOT)) || it.name.lowercase(locale).contains(localizedNeedle)
        }
    }
    val (favoriteMatches, otherMatches) = remember(matches, favorites) {
        matches.partition { it.code in favorites }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        sheetMaxWidth = ContentWidth,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeading(stringResource(title), Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(stringResource(R.string.done)) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.search_currencies)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, stringResource(R.string.clear_search))
                        }
                    }
                },
                singleLine = true,
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp, max = 480.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 14.dp),
            ) {
                if (favoriteMatches.isNotEmpty()) {
                    item("favorites") { PickerGroupLabel(R.string.favorites) }
                }
                items(favoriteMatches, key = { it.code }) { info ->
                    PickerRow(info, true, info.code in selectedCodes, multiSelect, onSelect, onToggleFavorite)
                }
                if (otherMatches.isNotEmpty()) {
                    item("currencies") { PickerGroupLabel(R.string.all_currencies) }
                }
                items(otherMatches, key = { it.code }) { info ->
                    PickerRow(info, false, info.code in selectedCodes, multiSelect, onSelect, onToggleFavorite)
                }
                if (matches.isEmpty()) {
                    item("empty") {
                        MutedText(stringResource(R.string.no_currencies), Modifier.padding(vertical = 14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerGroupLabel(label: Int) {
    Text(
        stringResource(label),
        Modifier.padding(top = 9.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun PickerRow(
    info: CurrencyInfo,
    favorite: Boolean,
    chosen: Boolean,
    multiSelect: Boolean,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val action = if (multiSelect) {
        stringResource(if (chosen) R.string.toggle_target_remove else R.string.toggle_target_add, info.code)
    } else null
    val favoriteAction = stringResource(
        if (favorite) R.string.remove_favorite else R.string.add_favorite,
        info.code,
    )
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).testTag("pick-${info.code}")
                .semantics { selected = chosen }
                .clickable(role = Role.Button, onClickLabel = action) { onSelect(info.code) }
                .padding(vertical = 8.dp, horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CurrencyMark(info.symbol)
            Column(Modifier.weight(1f)) {
                Text(info.code, style = MaterialTheme.typography.titleSmall)
                if (info.name != info.code) MutedText(info.name)
            }
            if (chosen) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        IconToggleButton(
            checked = favorite,
            onCheckedChange = { onToggleFavorite(info.code) },
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = favoriteAction,
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
