package com.pocketfx.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration

val RateTtl: Duration = Duration.ofMinutes(10)
val HistoryRetention: Duration = Duration.ofDays(365)

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
private val ConversionPrecision = MathContext.DECIMAL128

fun isCurrencyCode(value: String): Boolean = CurrencyCodePattern.matches(value)

/** Returns canonical input, including empty text and a trailing decimal separator while editing. */
fun normalizeAmount(input: String): String? {
    val canonical = buildString(input.length) {
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
    return canonical.takeIf(AmountPattern::matches)
}

fun parseAmount(input: String): BigDecimal? = normalizeAmount(input)?.toBigDecimalOrNull()

fun convert(
    amount: BigDecimal,
    source: String,
    target: String,
    usdRates: Map<String, BigDecimal>,
): BigDecimal? {
    if (amount.signum() < 0) return null
    val from = usdRates[source]?.takeIf { it.signum() > 0 } ?: return null
    val to = usdRates[target]?.takeIf { it.signum() > 0 } ?: return null
    if (source == target) return amount
    return amount.multiply(to).divide(from, ConversionPrecision)
}

fun isCacheFresh(lastCheckedAt: Long?, now: Long): Boolean =
    lastCheckedAt != null && lastCheckedAt <= now && now - lastCheckedAt < RateTtl.toMillis()

data class HistoryPoint(val timestamp: Long, val rate: BigDecimal)

data class HistoryStats(
    val latest: BigDecimal,
    val minimum: BigDecimal,
    val maximum: BigDecimal,
    val changePercent: BigDecimal,
)

fun historicalRates(snapshots: List<RateSnapshot>, source: String, target: String): List<HistoryPoint> =
    snapshots.mapNotNull { snapshot ->
        convert(BigDecimal.ONE, source, target, snapshot.rates)?.let {
            HistoryPoint(snapshot.providerUpdatedAt, it)
        }
    }.sortedBy(HistoryPoint::timestamp)

fun historyStats(points: List<HistoryPoint>): HistoryStats? {
    val first = points.firstOrNull()?.rate ?: return null
    val latest = points.last().rate
    return HistoryStats(
        latest = latest,
        minimum = points.minOf(HistoryPoint::rate),
        maximum = points.maxOf(HistoryPoint::rate),
        changePercent = (latest - first).movePointRight(2).divide(first, 12, RoundingMode.HALF_EVEN),
    )
}
