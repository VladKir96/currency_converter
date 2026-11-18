package com.pocketfx.ui.history

import com.pocketfx.data.repository.HistoryState
import com.pocketfx.domain.HistoryPoint
import com.pocketfx.domain.HistoryStats
import com.pocketfx.domain.historyStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.math.MathContext

internal class HistoryChartData(
    val points: List<HistoryPoint>,
    val stats: HistoryStats,
    val x: FloatArray,
    val y: FloatArray,
) {
    fun nearestIndex(position: Float): Int {
        val found = x.binarySearch(position)
        if (found >= 0) return found
        val after = (-found - 1).coerceAtMost(x.lastIndex)
        if (after == 0 || position - x[after - 1] > x[after] - position) return after
        return after - 1
    }

    companion object {
        fun from(points: List<HistoryPoint>): HistoryChartData? {
            val stats = historyStats(points) ?: return null
            val first = points.first().date.toEpochDay()
            val spread = (points.last().date.toEpochDay() - first).toFloat()
            if (spread <= 0f) return null
            val rateRange = stats.maximum - stats.minimum
            val flat = rateRange.signum() == 0
            return HistoryChartData(
                points = points,
                stats = stats,
                x = FloatArray(points.size) { (points[it].date.toEpochDay() - first) / spread },
                y = FloatArray(points.size) {
                    if (flat) 0.5f
                    else (stats.maximum - points[it].rate).divide(rateRange, MathContext.DECIMAL64).toFloat()
                },
            )
        }
    }
}

internal data class HistoryPresentation(
    val state: HistoryState = HistoryState(),
    val chart: HistoryChartData? = null,
)

internal fun Flow<HistoryState>.prepareChart(): Flow<HistoryPresentation> = flow {
    var previous: List<HistoryPoint> = emptyList()
    var chart: HistoryChartData? = null
    collect { state ->
        val points = state.series?.points.orEmpty()
        if (points != previous) {
            previous = points
            chart = HistoryChartData.from(points)
        }
        emit(HistoryPresentation(state, chart))
    }
}.flowOn(Dispatchers.Default)
