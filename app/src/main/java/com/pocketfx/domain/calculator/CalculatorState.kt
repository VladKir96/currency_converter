package com.pocketfx.domain.calculator

import com.pocketfx.domain.normalizeAmount
import com.pocketfx.domain.parseAmount
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal enum class CalculatorKey(val symbol: String) {
    ZERO("0"), ONE("1"), TWO("2"), THREE("3"), FOUR("4"), FIVE("5"),
    SIX("6"), SEVEN("7"), EIGHT("8"), NINE("9"), TRIPLE_ZERO("000"), DECIMAL("."),
    ADD("+"), SUBTRACT("−"), MULTIPLY("×"), DIVIDE("÷"),
    SIGN("±"), CLEAR("C"), BACKSPACE("⌫"), EQUALS("=");

    val isOperator: Boolean get() = this in ADD..DIVIDE
}

internal enum class CalculatorError { INCOMPLETE, DIVISION_BY_ZERO, TOO_LONG, TOO_LARGE, NEGATIVE_AMOUNT, AMOUNT_TOO_LARGE }

private data class Term(val number: String, val following: CalculatorKey)
private const val DigitLimit = 34
private const val OperatorLimit = 32
private const val DisplayLimit = 100
private val Precision = MathContext.DECIMAL128

internal class CalculatorState private constructor(
    private val terms: List<Term>,
    private val entry: String?,
    private val evaluated: Boolean = false,
    private val inputError: CalculatorError? = null,
) {
    fun expression(formatOperand: (String) -> String): String = buildString {
        terms.forEach {
            append(formatOperand(it.number))
            append(' ')
            append(it.following.symbol)
            append(' ')
        }
        entry?.let { append(formatOperand(it)) }
    }.trimEnd()

    private val calculation = evaluate(terms, entry)
    val result: BigDecimal? = calculation.value
    val amount: String? = result?.takeIf { inputError == null && it.signum() >= 0 }
        ?.setScale(6, RoundingMode.HALF_EVEN)?.stripTrailingZeros()?.toPlainString()?.let(::normalizeAmount)

    val error: CalculatorError? = inputError ?: calculation.error?.takeUnless {
        it == CalculatorError.INCOMPLETE && !evaluated
    } ?: when {
        result == null -> null
        result.signum() < 0 -> CalculatorError.NEGATIVE_AMOUNT
        amount == null -> CalculatorError.AMOUNT_TOO_LARGE
        else -> null
    }

    fun press(key: CalculatorKey): CalculatorState = when {
        key == CalculatorKey.CLEAR -> start("")
        key == CalculatorKey.EQUALS -> CalculatorState(terms, entry, evaluated = true)
        key.isOperator -> appendOperator(key)
        key == CalculatorKey.SIGN -> editEntry { if (it?.startsWith('-') == true) it.drop(1) else "-${it ?: "0"}" }
        key == CalculatorKey.BACKSPACE -> {
            if (entry == null && terms.isNotEmpty()) CalculatorState(terms.dropLast(1), terms.last().number)
            else editEntry { value -> value.orEmpty().dropLast(1).takeUnless { it.isEmpty() || it == "-" } ?: "0" }
        }
        key == CalculatorKey.TRIPLE_ZERO ->
            appendDigit(CalculatorKey.ZERO).appendDigit(CalculatorKey.ZERO).appendDigit(CalculatorKey.ZERO)
        else -> appendDigit(key)
    }

    private fun appendOperator(key: CalculatorKey): CalculatorState {
        if (evaluated && result != null) return CalculatorState(listOf(Term(result.calculatorText(), key)), null)
        if (entry == null) {
            return CalculatorState(terms.dropLast(1) + terms.last().copy(following = key), null)
        }
        if (terms.size >= OperatorLimit) return CalculatorState(terms, entry, inputError = CalculatorError.TOO_LONG)
        return CalculatorState(terms + Term(entry, key), null)
    }

    private fun appendDigit(key: CalculatorKey): CalculatorState {
        val previous = entry.takeUnless { evaluated && result != null }
        val preceding = if (evaluated && result != null) emptyList() else terms
        val current = previous ?: "0"
        val next = if (key == CalculatorKey.DECIMAL) {
            if ('.' in current) current else "$current."
        } else when (current) {
            "0" -> key.symbol
            "-0" -> "-${key.symbol}"
            else -> current + key.symbol
        }
        if (next.count(Char::isDigit) > DigitLimit) {
            return CalculatorState(terms, entry, inputError = CalculatorError.TOO_LONG)
        }
        return CalculatorState(preceding, next)
    }

    private fun editEntry(change: (String?) -> String): CalculatorState =
        if (evaluated && result != null) CalculatorState(emptyList(), change(result.calculatorText()))
        else CalculatorState(terms, change(entry))

    companion object {
        fun start(amount: String): CalculatorState =
            CalculatorState(emptyList(), parseAmount(amount)?.calculatorText() ?: "0")
    }
}

internal fun BigDecimal.calculatorText(): String = stripTrailingZeros().toPlainString()

private data class Calculation(val value: BigDecimal? = null, val error: CalculatorError? = null)

/** Accumulate additive terms while applying multiplication and division within the active term. */
private fun evaluate(terms: List<Term>, entry: String?): Calculation {
    if (entry == null) return Calculation(error = CalculatorError.INCOMPLETE)
    if (terms.isEmpty()) return Calculation(entry.toBigDecimal())
    var sum = BigDecimal.ZERO
    var term = terms.first().number.toBigDecimal()
    for ((index, token) in terms.withIndex()) {
        val right = (terms.getOrNull(index + 1)?.number ?: entry).toBigDecimal()
        when (token.following) {
            CalculatorKey.ADD -> {
                sum = sum.add(term, Precision)
                term = right
            }
            CalculatorKey.SUBTRACT -> {
                sum = sum.add(term, Precision)
                term = right.negate()
            }
            CalculatorKey.MULTIPLY -> term = term.multiply(right, Precision)
            CalculatorKey.DIVIDE -> {
                if (right.signum() == 0) return Calculation(error = CalculatorError.DIVISION_BY_ZERO)
                term = term.divide(right, Precision)
            }
            else -> error("Expected an arithmetic operator")
        }
        if (term.exceedsDisplay() || sum.exceedsDisplay()) return Calculation(error = CalculatorError.TOO_LARGE)
    }
    val total = sum.add(term, Precision)
    return if (total.exceedsDisplay()) Calculation(error = CalculatorError.TOO_LARGE) else Calculation(total)
}

private fun BigDecimal.exceedsDisplay(): Boolean =
    signum() != 0 && precision() - scale() !in -DisplayLimit..DisplayLimit
