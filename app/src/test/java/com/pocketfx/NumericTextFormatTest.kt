package com.pocketfx

import com.pocketfx.ui.components.NumericTextFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class NumericTextFormatTest {
    private val english = NumericTextFormat(Locale.US)
    private val russian = NumericTextFormat(Locale.forLanguageTag("ru-RU"))
    private val serbian = NumericTextFormat(Locale.forLanguageTag("sr-Latn-RS"))

    @Test fun groupsIntegersWithoutChangingPartialInputOrPrecision() {
        val examples = mapOf(
            "" to "",
            "." to ".",
            "0" to "0",
            "999" to "999",
            "1000" to "1,000",
            "1000000." to "1,000,000.",
            "-125000.50" to "-125,000.50",
            "1000000000.1200000000000003" to "1,000,000,000.1200000000000003",
            ".000" to ".000",
        )
        examples.forEach { (raw, display) -> assertEquals(display, english.format(raw)) }
        assertEquals("1${russian.groupingSeparator}234${russian.groupingSeparator}567,890", russian.format("1234567.890"))
        assertEquals("1.234.567,890", serbian.format("1234567.890"))
        assertEquals("1.000,", serbian.format("1000."))
    }

    @Test fun pastedLocalizedNumbersNormalizeWithoutLosingTrailingZeros() {
        val samples = listOf("1000.50", "1,000.50", "1 000,50", "1 000.50", "1.000,50", "1\u00a0000,50", "1\u202f000.50")
        for (format in listOf(english, russian, serbian)) {
            assertEquals("1000", format.parseEdit("1000"))
            for (sample in samples) assertEquals(sample, "1000.50", format.parseEdit(sample))
            assertEquals(".000", format.parseEdit(".000"))
            assertEquals("1.", format.parseEdit("1,"))
            assertEquals(".", format.parseEdit(","))
            assertEquals("12.30", format.parseEdit(" ١٢,٣٠ "))
            assertEquals("", format.parseEdit(""))
        }
    }

    @Test fun localeResolvesAmbiguityAndNumbersRoundTrip() {
        assertEquals("1234", english.parseEdit("1,234"))
        assertEquals("1.234", english.parseEdit("1.234"))
        assertEquals("1.234", russian.parseEdit("1,234"))
        assertEquals("1.234", russian.parseEdit("1.234"))
        assertEquals("1.234", serbian.parseEdit("1,234"))
        assertEquals("1234", serbian.parseEdit("1.234"))
        for (format in listOf(english, russian, serbian)) {
            for (raw in listOf("1000", "1000000.1234", "1.234", "999.10", "1234567890.12345678901234567890")) {
                assertEquals(raw, format.parseEdit(format.format(raw)))
            }
        }
    }

    @Test fun malformedClipboardValuesCannotBeSilentlyRewritten() {
        for (value in listOf("text", "  ", "1,23,456", "1.2.3", "1,234.56.78", "1.50 USD", "1e3", "-1", "1\n000")) {
            assertNull(value, english.parseEdit(value))
        }
    }
}
