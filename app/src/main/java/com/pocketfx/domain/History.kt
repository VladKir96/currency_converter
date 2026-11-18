package com.pocketfx.domain

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

internal val HistoryPeriods = listOf(7, 30, 90)

internal data class HistoryQuery(
    val source: String,
    val target: String,
    val from: LocalDate,
    val to: LocalDate,
) {
    companion object {
        fun endingOn(source: String, target: String, days: Int, date: LocalDate): HistoryQuery =
            HistoryQuery(source, target, date.minusDays(days.toLong() - 1), date)
    }
}

internal data class HistoryPoint(val date: LocalDate, val rate: BigDecimal)

internal data class HistorySeries(
    val query: HistoryQuery,
    val fetchedAt: Long,
    val points: List<HistoryPoint>,
)

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
