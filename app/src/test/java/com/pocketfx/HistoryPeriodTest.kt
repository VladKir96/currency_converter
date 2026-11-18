package com.pocketfx

import com.pocketfx.domain.HistoryPeriod
import com.pocketfx.domain.HistoryPoint
import com.pocketfx.domain.HistoryQuery
import com.pocketfx.ui.history.HistoryChartData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class HistoryPeriodTest {
    @Test fun calendarYearsHandleLeapDayAndPreserveTheSelectedPeriod() {
        val leapDay = LocalDate.of(2024, 2, 29)
        val year = HistoryQuery.endingOn("USD", "RSD", HistoryPeriod.YEAR, leapDay)
        val five = HistoryQuery.endingOn("USD", "RSD", HistoryPeriod.FIVE_YEARS, leapDay)
        assertEquals(LocalDate.of(2023, 2, 28), year.from)
        assertEquals(LocalDate.of(2019, 2, 28), five.from)
        assertEquals(HistoryPeriod.FIVE_YEARS, five.reversed().period)
        assertEquals(leapDay, five.reversed().to)
        assertEquals(LocalDate.of(2023, 9, 2), HistoryPeriod.HALF_YEAR.start(leapDay))
    }

    @Test fun chartSelectionUsesActualDatesAndKeepsEachObservation() {
        val start = LocalDate.of(2021, 1, 1)
        val points = listOf(
            HistoryPoint(start, BigDecimal("2")),
            HistoryPoint(start.plusDays(4), BigDecimal("3")),
            HistoryPoint(start.plusDays(10), BigDecimal("1")),
        )
        val chart = requireNotNull(HistoryChartData.from(points))
        assertEquals(points, chart.points)
        assertEquals(0, chart.nearestIndex(-1f))
        assertEquals(1, chart.nearestIndex(0.5f))
        assertEquals(2, chart.nearestIndex(1.2f))
        assertEquals(0f, chart.y[1], 0f)
        assertEquals(1f, chart.y[2], 0f)
        assertNull(HistoryChartData.from(points.take(1)))
    }
}
