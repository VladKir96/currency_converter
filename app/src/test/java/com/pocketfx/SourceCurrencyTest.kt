package com.pocketfx

import com.pocketfx.domain.UserPreferences
import com.pocketfx.domain.withSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SourceCurrencyTest {
    private val initial = UserPreferences(favorites = listOf("JPY", "RUB"))

    @Test fun replacesTheNewSourceAtItsExistingTargetPosition() {
        assertEquals(initial.copy(sourceCurrency = "USD", targets = listOf("EUR", "RSD", "RUB")), initial.withSource("USD"))
        assertEquals(initial.copy(sourceCurrency = "RSD", targets = listOf("USD", "EUR", "RUB")), initial.withSource("RSD"))
    }

    @Test fun appendsTheOldSourceIfTheNewSourceWasNotATarget() {
        assertEquals(
            initial.copy(sourceCurrency = "GBP", targets = listOf("USD", "RSD", "RUB", "EUR")),
            initial.withSource("GBP"),
        )
        assertEquals(listOf("EUR"), initial.copy(targets = emptyList()).withSource("GBP").targets)
    }

    @Test fun switchingBackRestoresOriginalOrderAndFavorites() {
        assertEquals(initial, initial.withSource("USD").withSource("EUR"))
        assertSame(initial, initial.withSource("EUR"))
        assertSame(initial, initial.withSource("bad"))
    }

    @Test fun removesDuplicatesFromLegacyLists() {
        val legacy = initial.copy(targets = listOf("USD", "EUR", "RUB", "USD", "RUB"))
        assertEquals(listOf("EUR", "RUB"), legacy.withSource("USD").targets)
    }
}
