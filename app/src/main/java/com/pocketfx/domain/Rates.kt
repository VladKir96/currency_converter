package com.pocketfx.domain

import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration

val RateTtl: Duration = Duration.ofMinutes(10)

data class RateSnapshot(
    val provider: String,
    val baseCurrency: String,
    val providerUpdatedAt: Long,
    val fetchedAt: Long,
    val rates: Map<String, BigDecimal>,
)

data class CachedRates(
    val snapshot: RateSnapshot,
    val lastCheckedAt: Long,
    val nextProviderUpdate: Long?,
)

private val AmountPattern = Regex("[0-9]{0,12}(\\.[0-9]{0,6})?")
private val CurrencyCodePattern = Regex("[A-Z]{3}")
internal val RatePrecision: MathContext = MathContext.DECIMAL128

fun isCurrencyCode(value: String): Boolean = CurrencyCodePattern.matches(value)

/** Returns canonical input, including empty text and a trailing decimal separator while editing. */
fun normalizeAmount(input: String): String? = canonicalDecimal(input)?.takeIf(AmountPattern::matches)

internal fun canonicalDecimal(input: String): String? =
    buildString(input.length) {
        input.forEach { char ->
            when (char) {
                '.', ',' -> append('.')
                else -> {
                    val digit = char.digitToIntOrNull() ?: return null
                    append(digit)
                }
            }
        }
    }

fun parseAmount(input: String): BigDecimal? = normalizeAmount(input)?.toBigDecimalOrNull()

internal data class ApiCrossRate(private val from: BigDecimal, private val to: BigDecimal) {
    val value: BigDecimal = to.divide(from, RatePrecision)

    // Apply the USD factors before rounding, so a repeating cross-rate cannot move a money rounding tie.
    fun convert(amount: BigDecimal): BigDecimal = amount.multiply(to).divide(from, RatePrecision)
}

internal fun apiCrossRate(
    source: String,
    target: String,
    usdRates: Map<String, BigDecimal>,
): ApiCrossRate? {
    val from = usdRates[source]?.takeIf { it.signum() > 0 } ?: return null
    val to = usdRates[target]?.takeIf { it.signum() > 0 } ?: return null
    return ApiCrossRate(from, to)
}

fun isCacheFresh(lastCheckedAt: Long?, now: Long): Boolean =
    lastCheckedAt != null && lastCheckedAt <= now && now - lastCheckedAt < RateTtl.toMillis()
