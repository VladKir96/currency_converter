package com.pocketfx

import com.pocketfx.domain.HistoryPoint
import com.pocketfx.domain.RateSnapshot
import com.pocketfx.domain.convert
import com.pocketfx.domain.historicalRates
import com.pocketfx.domain.historyStats
import com.pocketfx.domain.isCacheFresh
import com.pocketfx.domain.normalizeAmount
import com.pocketfx.domain.parseAmount
import com.pocketfx.ui.components.asMoney
import com.pocketfx.ui.components.asRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class RateMathTest {
    private val rates = mapOf("USD" to "1", "EUR" to "0.8", "RSD" to "100", "RUB" to "92.1")
        .mapValues { it.value.toBigDecimal() }

    @Test fun usdToEur() = assertConversion("100", "USD", "EUR", "80")
    @Test fun eurToUsd() = assertConversion("100", "EUR", "USD", "125")
    @Test fun eurToRsd() = assertConversion("100", "EUR", "RSD", "12500")
    @Test fun eurToRub() = assertConversion("100", "EUR", "RUB", "11512.5")
    @Test fun sameCurrency() = assertConversion("13.020001", "EUR", "EUR", "13.020001")
    @Test fun zeroAmount() = assertConversion("0", "EUR", "RSD", "0")
    @Test fun crossRate() = assertConversion("800", "RSD", "EUR", "6.4")

    @Test fun keepsDecimalPrecision() {
        val precise = rates + ("EUR" to "0.123456789123456789".toBigDecimal())
        assertConversion("123456789123.123456", "USD", "EUR", "15241578780.63252629373373152834278", precise)
        val small = BigDecimal("0.00000000006789")
        assertEquals("0.0000000000679", small.asMoney(Locale.US))
        assertEquals("0.0000000000679", small.asRate(Locale.US))
        assertEquals("12,50", BigDecimal("12.5").asMoney(Locale.forLanguageTag("ru-RU")))
    }

    @Test fun validatesAmountInput() {
        listOf("", ".", ",", "1e4", "-1", "1.2.3", "1,234.56", "1234567890123", "1.1234567").forEach {
            assertNull(it, parseAmount(it))
        }
        assertEquals("", normalizeAmount(""))
        assertEquals("42.", normalizeAmount("42,"))
        assertEquals(BigDecimal("1.23"), parseAmount("١,٢٣"))
        assertEquals(BigDecimal("123456789012.123456"), parseAmount("123456789012.123456"))
        assertNull(convert(BigDecimal("-2"), "EUR", "USD", rates))
    }

    @Test fun rejectsMissingOrNonPositiveRates() {
        assertNull(convert(BigDecimal.ONE, "EUR", "JPY", rates))
        assertNull(convert(BigDecimal.ONE, "JPY", "JPY", rates))
        assertNull(convert(BigDecimal.ONE, "EUR", "USD", rates + ("EUR" to BigDecimal.ZERO)))
        assertNull(convert(BigDecimal.ONE, "USD", "EUR", rates + ("EUR" to BigDecimal.ONE.negate())))
    }

    @Test fun historyUsesPublishedTimesAndSkipsMissingObservations() {
        val snapshots = listOf(
            RateSnapshot("test", "USD", 90, 999, rates),
            RateSnapshot("test", "USD", 30, 900, rates - "EUR"),
            RateSnapshot("test", "USD", 10, 800, rates + ("EUR" to BigDecimal("0.5"))),
        )
        assertEquals(
            listOf(HistoryPoint(10, BigDecimal("2")), HistoryPoint(90, BigDecimal("1.25"))),
            historicalRates(snapshots, "EUR", "USD"),
        )
    }

    @Test fun computesHistoryExtremesAndChange() {
        val values = listOf("4", "3", "7", "5").mapIndexed { index, value ->
            HistoryPoint(index.toLong(), value.toBigDecimal())
        }
        val stats = requireNotNull(historyStats(values))
        assertEquals(BigDecimal("5"), stats.latest)
        assertEquals(BigDecimal("3"), stats.minimum)
        assertEquals(BigDecimal("7"), stats.maximum)
        assertEquals(0, stats.changePercent.compareTo(BigDecimal("25")))
        assertEquals(0, requireNotNull(historyStats(values.reversed())).changePercent.compareTo(BigDecimal("-20")))
        assertNull(historyStats(emptyList()))
    }

    @Test fun cacheExpiresAtExactlyTenMinutes() {
        val check = 1_000_000L
        assertTrue(isCacheFresh(check, check))
        assertTrue(isCacheFresh(check, check + 599_999))
        assertFalse(isCacheFresh(check, check + 600_000))
        assertFalse(isCacheFresh(check, check - 1))
        assertFalse(isCacheFresh(null, check))
    }

    private fun assertConversion(
        amount: String,
        from: String,
        to: String,
        expected: String,
        dataset: Map<String, BigDecimal> = rates,
    ) {
        val actual = requireNotNull(convert(amount.toBigDecimal(), from, to, dataset))
        assertEquals(expected, 0, actual.compareTo(expected.toBigDecimal()))
    }
}
