package com.pocketfx.domain

import java.util.Locale

val DefaultCurrencies = listOf("EUR", "USD", "RSD", "RUB")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage(val locale: Locale) {
    ENGLISH(Locale.US),
    RUSSIAN(Locale.forLanguageTag("ru-RU")),
    SERBIAN_LATIN(Locale.forLanguageTag("sr-Latn-RS")),
}

data class UserPreferences(
    val sourceCurrency: String = "EUR",
    val sourceAmount: String = "100",
    val targets: List<String> = listOf("USD", "RSD", "RUB"),
    val favorites: List<String> = DefaultCurrencies,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.ENGLISH,
)

internal fun UserPreferences.withSource(code: String): UserPreferences {
    if (code == sourceCurrency || !isCurrencyCode(code)) return this
    val existingTargets = targets.filterNot { it == sourceCurrency }.distinct()
    val updated = if (code in existingTargets) {
        existingTargets.map { if (it == code) sourceCurrency else it }
    } else {
        existingTargets + sourceCurrency
    }
    return copy(sourceCurrency = code, targets = updated)
}

internal fun UserPreferences.withTargetOrder(order: List<String>): UserPreferences {
    if (order.size != targets.size || sourceCurrency in order || order.toSet() != targets.toSet()) return this
    return copy(targets = order.toList())
}
