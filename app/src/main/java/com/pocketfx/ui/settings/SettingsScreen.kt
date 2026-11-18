package com.pocketfx.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pocketfx.BuildConfig
import com.pocketfx.R
import com.pocketfx.data.repository.RefreshStatus
import com.pocketfx.domain.AppLanguage
import com.pocketfx.domain.ThemeMode
import com.pocketfx.domain.UserPreferences
import com.pocketfx.ui.PocketFxViewModel
import com.pocketfx.ui.RateCacheState
import com.pocketfx.ui.components.ContentWidth
import com.pocketfx.ui.components.MutedText
import com.pocketfx.ui.components.Panel
import com.pocketfx.ui.components.PocketTopBar
import com.pocketfx.ui.components.RateAttribution
import com.pocketfx.ui.components.RateStatusPanel
import com.pocketfx.ui.components.SectionHeading

@Composable
internal fun SettingsScreen(
    prefs: UserPreferences,
    cache: RateCacheState,
    status: RefreshStatus,
    online: Boolean,
    now: Long,
    model: PocketFxViewModel,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { PocketTopBar(R.string.settings, onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item("theme") {
                    Panel {
                        SectionHeading(stringResource(R.string.appearance))
                        MutedText(stringResource(R.string.theme), Modifier.padding(top = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (theme in ThemeMode.entries) {
                                val label = when (theme) {
                                    ThemeMode.SYSTEM -> R.string.theme_system
                                    ThemeMode.LIGHT -> R.string.theme_light
                                    ThemeMode.DARK -> R.string.theme_dark
                                }
                                FilterChip(
                                    selected = prefs.theme == theme,
                                    onClick = { model.setTheme(theme) },
                                    label = { Text(stringResource(label)) },
                                )
                            }
                        }
                    }
                }
                item("language") {
                    Panel {
                        SectionHeading(stringResource(R.string.language))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (language in AppLanguage.entries) {
                                FilterChip(
                                    selected = prefs.language == language,
                                    onClick = { model.setLanguage(language) },
                                    label = {
                                        val name = when (language) {
                                            AppLanguage.ENGLISH -> R.string.language_english
                                            AppLanguage.RUSSIAN -> R.string.language_russian
                                            AppLanguage.SERBIAN_LATIN -> R.string.language_serbian
                                        }
                                        Text(stringResource(name))
                                    },
                                )
                            }
                        }
                    }
                }
                item("rates_heading") { SectionHeading(stringResource(R.string.rate_data)) }
                item("rates") {
                    RateStatusPanel(cache.data, status, cache.failed, online, now) { model.refresh(force = true) }
                }
                item("about") {
                    Panel {
                        SectionHeading(stringResource(R.string.about))
                        Text(stringResource(R.string.app_name), Modifier.padding(top = 8.dp))
                        MutedText(stringResource(R.string.version, BuildConfig.VERSION_NAME))
                        RateAttribution()
                        MutedText(stringResource(R.string.disclaimer))
                    }
                }
            }
        }
    }
}
