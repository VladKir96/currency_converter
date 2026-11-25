package com.pocketfx

import com.pocketfx.domain.calculator.CalculatorError
import com.pocketfx.domain.calculator.CalculatorKey
import com.pocketfx.domain.calculator.CalculatorKey.*
import com.pocketfx.domain.calculator.CalculatorState
import com.pocketfx.domain.calculator.calculatorText
import com.pocketfx.ui.components.NumericTextFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class CalculatorTest {
    @Test fun appliesPrecedenceAndAssociativity() {
        assertResult("100", "150", ADD, TWO, FIVE, MULTIPLY, TWO, EQUALS)
        assertResult("12", "15", ADD, THREE, EQUALS)
        assertResult("12", "9", SUBTRACT, THREE, EQUALS)
        assertResult("12", "36", MULTIPLY, THREE, EQUALS)
        assertResult("12", "4", DIVIDE, THREE, EQUALS)
        assertResult("24", "4", DIVIDE, SIX, MULTIPLY, TWO, SUBTRACT, FOUR, EQUALS)
    }

    @Test fun usesBigDecimalsAndRoundsOnlyTheAppliedAmount() {
        assertResult("0,1", "0.3", ADD, DECIMAL, TWO, EQUALS)
        val third = press("1", DIVIDE, THREE, EQUALS)
        assertEquals("0.3333333333333333333333333333333333", third.result?.calculatorText())
        assertEquals("0.333333", third.amount)
        assertEquals("0.000002", press("", TWO, DECIMAL, FIVE, DIVIDE, ONE, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, EQUALS).amount)
    }

    @Test fun handlesSignedOperandsAndNegativeIntermediateResults() {
        assertResult("5", "6", SUBTRACT, EIGHT, EQUALS, MULTIPLY, SIGN, TWO, EQUALS)
        val negative = press("2", SUBTRACT, THREE, EQUALS)
        assertEquals("-1", negative.result?.calculatorText())
        assertEquals(CalculatorError.NEGATIVE_AMOUNT, negative.error)
        assertNull(negative.amount)
    }

    @Test fun divisionByZeroAndIncompleteExpressionsCannotBeApplied() {
        val divideByZero = press("100", DIVIDE, ZERO, EQUALS)
        assertEquals(CalculatorError.DIVISION_BY_ZERO, divideByZero.error)
        assertNull(divideByZero.amount)
        val incomplete = press("100", ADD, EQUALS)
        assertEquals(CalculatorError.INCOMPLETE, incomplete.error)
        assertNull(incomplete.amount)
        assertResult("100", "200", ADD, MULTIPLY, TWO, EQUALS)
    }

    @Test fun clearBackspaceAndNewDigitsAfterEqualsBehavePredictably() {
        assertResult("137", "13", BACKSPACE)
        assertResult("137", "137", ADD, BACKSPACE)
        assertResult("137", "0", CLEAR)
        assertResult("137", "9", ADD, ONE, EQUALS, NINE)
        assertResult("0", "0.1", DECIMAL, DECIMAL, ONE)
        assertResult("invalid amount", "0")
    }

    @Test fun tripleZeroUsesTheSameOperandRulesAsIndividualZeros() {
        assertResult("1", "1000", TRIPLE_ZERO)
        assertResult("25", "25000", TRIPLE_ZERO)
        assertResult("0", "0", TRIPLE_ZERO)
        assertResult("0", "0", SIGN, TRIPLE_ZERO)
        assertEquals("0.2000", press("0.2", TRIPLE_ZERO).expression { it })
        assertEquals("100 + 0", press("100", ADD, TRIPLE_ZERO).expression { it })
        assertEquals("100 + 5000", press("100", ADD, FIVE, TRIPLE_ZERO).expression { it })
        assertResult("12", "1200", TRIPLE_ZERO, BACKSPACE)
        assertResult("2", "0", ADD, ONE, EQUALS, TRIPLE_ZERO)

        val nearLimit = press("", *List(32) { NINE }.toTypedArray())
        val individual = nearLimit.press(ZERO).press(ZERO).press(ZERO)
        val triple = nearLimit.press(TRIPLE_ZERO)
        assertEquals(CalculatorError.TOO_LONG, triple.error)
        assertEquals(individual.expression { it }, triple.expression { it })
        assertEquals(individual.result, triple.result)
    }

    @Test fun formatsIndividualOperandsAndNegativeResultsWithoutChangingArithmetic() {
        val numbers = NumericTextFormat(Locale.US)
        val grouped = press("125000", ADD, THREE, FIVE, TRIPLE_ZERO, MULTIPLY, TWO)
        assertEquals("125,000 + 35,000 × 2", grouped.expression(numbers::format))
        assertEquals("195000", grouped.amount)
        val negative = press("125000.5", SIGN)
        assertEquals("-125,000.5", negative.expression(numbers::format))
        assertEquals("-125,000.5", numbers.format(requireNotNull(negative.result).calculatorText()))
    }

    @Test fun limitsInputAndRejectsAnAmountBeyondConverterPrecision() {
        val large = press("999999999999", ADD, ONE, EQUALS)
        assertEquals("1000000000000", large.result?.calculatorText())
        assertEquals(CalculatorError.AMOUNT_TOO_LARGE, large.error)
        assertNull(large.amount)
        val overflow = press("", *List(36) { NINE }.toTypedArray())
        assertEquals(CalculatorError.TOO_LONG, overflow.error)
        assertNull(overflow.amount)
        val longExpression = press("1", *(List(32) { listOf(ADD, ONE) }.flatten() + ADD).toTypedArray())
        assertEquals(CalculatorError.TOO_LONG, longExpression.error)
        assertNull(longExpression.amount)
    }

    private fun press(initial: String, vararg keys: CalculatorKey) =
        keys.fold(CalculatorState.start(initial)) { state, key -> state.press(key) }

    private fun assertResult(initial: String, expected: String, vararg keys: CalculatorKey) {
        assertEquals(expected, press(initial, *keys).result?.calculatorText())
    }
}
