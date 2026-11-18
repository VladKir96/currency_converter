package com.pocketfx.domain

import java.util.Locale

val DefaultCurrencies = listOf("EUR", "USD", "RSD", "RUB")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage(val locale: Locale) {
    ENGLISH(Locale.US),
    RUSSIAN(Locale.forLanguageTag("ru-RU")),
}

data class UserPreferences(
    val sourceCurrency: String = "EUR",
    val sourceAmount: String = "100",
    val targets: List<String> = listOf("USD", "RSD", "RUB"),
    val favorites: List<String> = DefaultCurrencies,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.ENGLISH,
)
