package com.pocketfx

import com.pocketfx.domain.CustomExchangeRate
import com.pocketfx.domain.parseCustomRate
import com.pocketfx.domain.resolveExchangeRate
import com.pocketfx.ui.components.asRateInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class CustomExchangeRateTest {
    private val api = mapOf("EUR" to "0.8", "USD" to "1", "RSD" to "100", "RUB" to "92.1")
        .mapValues { it.value.toBigDecimal() }
    private val rateOverride = CustomExchangeRate("EUR", "RSD", BigDecimal("117.50"))

    @Test fun customRateOverridesApiAndConvertsExactly() {
        val rates = resolveExchangeRate("EUR", "RSD", api, mapOf(rateOverride.pair to rateOverride))
        assertEquals(BigDecimal("125"), rates.apiRate)
        assertEquals(BigDecimal("117.50"), rates.effectiveRate)
        assertTrue(rates.isCustom)
        assertEquals(BigDecimal("11750.00"), rates.convert(BigDecimal("100")))
    }

    @Test fun storedDirectionDeterminesWhichSideUsesTheReciprocal() {
        val original = mapOf(rateOverride.pair to rateOverride)
        val reversed = resolveExchangeRate("RSD", "EUR", api, original)
        assertEquals(BigDecimal("0.008510638297872340425531914893617021"), reversed.effectiveRate)
        assertTrue(reversed.isCustom)

        val edit = CustomExchangeRate("RSD", "EUR", BigDecimal("0.0084"))
        val edited = original + (edit.pair to edit)
        assertEquals(1, edited.size)
        assertEquals(BigDecimal("0.0084"), resolveExchangeRate("RSD", "EUR", api, edited).effectiveRate)
        assertEquals(
            BigDecimal("119.0476190476190476190476190476190"),
            resolveExchangeRate("EUR", "RSD", api, edited).effectiveRate,
        )
    }

    @Test fun independentOverridesDoNotCreateOtherCustomRates() {
        val dollar = CustomExchangeRate("EUR", "USD", BigDecimal("1.2"))
        val custom = listOf(rateOverride, dollar).associateBy { it.pair }
        val rsd = resolveExchangeRate("USD", "RSD", api, custom)
        assertFalse(rsd.isCustom)
        assertEquals(BigDecimal("100"), rsd.effectiveRate)
        val rub = requireNotNull(resolveExchangeRate("EUR", "RUB", api, custom).convert(BigDecimal("100")))
        assertEquals(0, BigDecimal("11512.5").compareTo(rub))
    }

    @Test fun customRatesWorkWithoutAnApiDataset() {
        val rates = resolveExchangeRate("EUR", "RSD", emptyMap(), mapOf(rateOverride.pair to rateOverride))
        assertNull(rates.apiRate)
        assertEquals(BigDecimal("235.00"), rates.convert(BigDecimal("2")))
        assertNull(resolveExchangeRate("EUR", "RSD", emptyMap(), emptyMap()).effectiveRate)
    }

    @Test fun precisionAndLocalizedSeparatorsSurviveEditingAndConversion() {
        val locale = Locale.forLanguageTag("sr-Latn-RS")
        val input = "0,123456789123456789123456789123456789"
        val rate = requireNotNull(parseCustomRate(input))
        assertEquals(input, rate.asRateInput(locale))
        val custom = CustomExchangeRate("EUR", "RSD", rate)
        val result = resolveExchangeRate("EUR", "RSD", api, mapOf(custom.pair to custom)).convert(BigDecimal("100"))
        assertEquals(BigDecimal("12.345678912345678912345678912345678900"), result)
        assertEquals(BigDecimal("1.23"), parseCustomRate("١,٢٣"))

        val nearLimit = CustomExchangeRate("EUR", "USD", BigDecimal("999999999999999999"))
        val reciprocal = nearLimit.from("USD")
        assertEquals(reciprocal, parseCustomRate(reciprocal.asRateInput(locale)))
    }

    @Test fun invalidRatesCannotBeSaved() {
        listOf(
            "", ".", ",", "0", "0.0", "-0.1", "+1", "1e2", "Infinity", "1.2.3", "1,234.56",
            "1000000000000000001", "0.0000000000000000001", "1." + "2".repeat(53), "1".repeat(500),
        ).forEach { assertNull(it, parseCustomRate(it)) }
        assertEquals(BigDecimal("1000000000000000000"), parseCustomRate("1000000000000000000"))
        assertEquals(BigDecimal("0.000000000000000001"), parseCustomRate("0.000000000000000001"))
    }
}
