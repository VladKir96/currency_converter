package com.pocketfx.ui

import android.content.res.Configuration
import android.os.LocaleList
import android.view.Window
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pocketfx.R
import com.pocketfx.domain.AppLanguage
import com.pocketfx.domain.ThemeMode
import com.pocketfx.ui.components.LocalAppLocale
import com.pocketfx.ui.converter.ConverterScreen
import com.pocketfx.ui.history.HistoryScreen
import com.pocketfx.ui.settings.SettingsScreen
import com.pocketfx.ui.theme.PocketTheme
import com.pocketfx.ui.theme.useDarkTheme
import com.pocketfx.ui.update.UpdateDialogs
import com.pocketfx.ui.update.UpdateViewModel

@Composable
internal fun PocketFxApp(model: PocketFxViewModel, window: Window? = null, updates: UpdateViewModel? = null) {
    val nav = rememberNavController()
    val prefs by model.preferences.collectAsStateWithLifecycle()
    val cache by model.cache.collectAsStateWithLifecycle()
    val refreshStatus by model.refreshStatus.collectAsStateWithLifecycle()
    val online by model.online.collectAsStateWithLifecycle()
    val time by model.time.collectAsStateWithLifecycle()
    val readFailed by model.settingsReadFailed.collectAsStateWithLifecycle()
    val writeFailed by model.settingsWriteFailed.collectAsStateWithLifecycle()
    val dark = useDarkTheme(prefs?.theme ?: ThemeMode.SYSTEM)
    val locale = (prefs?.language ?: AppLanguage.ENGLISH).locale
    val context = LocalContext.current
    val systemConfiguration = LocalConfiguration.current
    val configuration = remember(systemConfiguration, locale) {
        Configuration(systemConfiguration).apply { setLocales(LocaleList(locale)) }
    }
    val localizedContext = remember(context, configuration) { context.createConfigurationContext(configuration) }

    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose {}
    }
    SideEffect {
        if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides configuration,
        LocalAppLocale provides locale,
    ) {
        PocketTheme(dark) {
            val readError = stringResource(R.string.error_preferences_read)
            val writeError = stringResource(R.string.error_preferences_write)
            LaunchedEffect(readFailed) {
                if (readFailed) Toast.makeText(localizedContext, readError, Toast.LENGTH_LONG).show()
            }
            LaunchedEffect(writeFailed) {
                if (writeFailed) Toast.makeText(localizedContext, writeError, Toast.LENGTH_LONG).show()
            }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val preferences = prefs
                if (preferences == null) {
                    Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    NavHost(navController = nav, startDestination = "converter") {
                        composable("converter") {
                            ConverterScreen(
                                preferences, cache, refreshStatus, online, time, model,
                                onHistory = { nav.navigate("history") { launchSingleTop = true } },
                                onSettings = { nav.navigate("settings") { launchSingleTop = true } },
                            )
                        }
                        composable("history") {
                            HistoryScreen(
                                preferences, model, cache.data?.snapshot?.rates?.keys.orEmpty(), time, online,
                                onBack = { nav.popBackStack() },
                                onToggleFavorite = model::toggleFavorite,
                            )
                        }
                        composable("settings") {
                            SettingsScreen(preferences, cache, refreshStatus, online, time, model, updates) { nav.popBackStack() }
                        }
                    }
                }
            }
            if (updates != null) {
                val state by updates.state.collectAsStateWithLifecycle()
                UpdateDialogs(state, updates)
            }
        }
    }
}
