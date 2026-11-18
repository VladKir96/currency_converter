package com.pocketfx.domain

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

internal enum class HistoryPeriod(val amount: Int, val years: Boolean = false) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
    HALF_YEAR(180),
    YEAR(1, years = true),
    FIVE_YEARS(5, years = true);

    fun start(end: LocalDate): LocalDate = when {
        years -> end.minusYears(amount.toLong())
        this == HALF_YEAR -> end.minusDays(amount.toLong())
        else -> end.minusDays(amount.toLong() - 1) // Preserve the existing inclusive short periods.
    }
}

internal data class HistoryQuery(
    val source: String,
    val target: String,
    val from: LocalDate,
    val to: LocalDate,
) {
    val period: HistoryPeriod?
        get() = HistoryPeriod.entries.firstOrNull { it.start(to) == from }

    fun reversed() = copy(source = target, target = source)

    companion object {
        fun endingOn(source: String, target: String, period: HistoryPeriod, date: LocalDate): HistoryQuery =
            HistoryQuery(source, target, period.start(date), date)
    }
}

internal data class HistoryPoint(val date: LocalDate, val rate: BigDecimal)

internal data class HistorySeries(
    val query: HistoryQuery,
    val fetchedAt: Long,
    val points: List<HistoryPoint>,
) {
    /** Keep the saved dates for an offline rollover, or slice a series that covers the requested range. */
    fun forQuery(request: HistoryQuery, slice: Boolean): HistorySeries {
        if (query == request) return this
        val reversed = query.source == request.target
        val observed = if (slice) points.filter { it.date in request.from..request.to } else points
        val values = if (reversed) {
            observed.map { it.copy(rate = BigDecimal.ONE.divide(it.rate, MathContext.DECIMAL128)) }
        } else observed
        val displayQuery = when {
            slice -> request
            reversed -> query.reversed()
            else -> query
        }
        return HistorySeries(displayQuery, fetchedAt, values)
    }
}

internal data class HistoryStats(
    val latest: BigDecimal,
    val minimum: BigDecimal,
    val maximum: BigDecimal,
    val changePercent: BigDecimal,
)

internal fun historyStats(points: List<HistoryPoint>): HistoryStats? {
    if (points.size < 2) return null
    val first = points.first().rate
    val latest = points.last().rate
    return HistoryStats(
        latest = latest,
        minimum = points.minOf(HistoryPoint::rate),
        maximum = points.maxOf(HistoryPoint::rate),
        changePercent = (latest - first).movePointRight(2).divide(first, MathContext.DECIMAL128),
    )
}
