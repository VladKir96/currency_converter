package com.pocketfx.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

internal val LocalAppLocale = staticCompositionLocalOf { Locale.US }

private fun decimalFormat(locale: Locale, minDigits: Int, maxDigits: Int): DecimalFormat =
    (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
        minimumFractionDigits = minDigits
        maximumFractionDigits = maxDigits
        roundingMode = RoundingMode.HALF_EVEN
    }

private fun adaptiveDigits(value: BigDecimal, standardDigits: Int): Int {
    if (value.signum() == 0) return standardDigits
    val significantPlace = value.precision() - value.scale() - 1
    return maxOf(standardDigits, -significantPlace + 2)
}

internal fun BigDecimal.asMoney(locale: Locale): String =
    decimalFormat(locale, 2, adaptiveDigits(this, 2)).format(this)

internal fun BigDecimal.asRate(locale: Locale): String =
    decimalFormat(locale, 0, adaptiveDigits(this, 6)).format(this)

internal fun BigDecimal.asPercent(locale: Locale): String =
    (NumberFormat.getPercentInstance(locale) as DecimalFormat).apply {
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_EVEN
        if (this@asPercent.signum() > 0) positivePrefix = "+" + positivePrefix
    }.format(movePointLeft(2))

internal fun formatTimestamp(timestamp: Long, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))

internal fun formatDay(timestamp: Long, locale: Locale, includeYear: Boolean = false): String =
    DateTimeFormatter.ofLocalizedDate(if (includeYear) FormatStyle.MEDIUM else FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))

internal data class CurrencyInfo(val code: String, val name: String, val symbol: String)

internal fun currencyInfo(code: String, locale: Locale): CurrencyInfo {
    val currency = runCatching { Currency.getInstance(code) }.getOrNull()
    val symbol = when (code) {
        "USD" -> "$"
        "EUR" -> "€"
        "RUB" -> "₽"
        "RSD" -> if (locale.language == "ru") "дин" else "din"
        else -> currency?.getSymbol(locale) ?: code
    }
    return CurrencyInfo(code, currency?.getDisplayName(locale) ?: code, symbol)
}
