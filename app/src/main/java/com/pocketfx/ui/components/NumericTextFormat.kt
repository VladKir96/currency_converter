package com.pocketfx.ui.components

import java.text.DecimalFormatSymbols
import java.util.Locale

internal class NumericTextFormat(locale: Locale) {
    private val symbols = DecimalFormatSymbols.getInstance(locale)
    val decimalSeparator: Char get() = symbols.decimalSeparator
    val groupingSeparator: Char get() = symbols.groupingSeparator

    fun groupingOffsets(text: CharSequence): IntProgression {
        val integerEnd = text.indexOf('.').takeIf { it >= 0 } ?: text.length
        val signLength = if (text.startsWith('-')) 1 else 0
        return (integerEnd - 3) downTo (signLength + 1) step 3
    }

    fun format(canonical: String): String = buildString {
        append(canonical.replace('.', decimalSeparator))
        for (offset in groupingOffsets(canonical)) insert(offset, groupingSeparator)
    }

    /** Normalize the newly inserted text. Separators already in the field are canonical. */
    fun parseEdit(input: CharSequence): String? {
        if (input.isEmpty()) return ""
        val trimmed = input.trim(::isGroupingSpace)
        if (trimmed.isEmpty()) return null
        val text = buildString(trimmed.length) {
            for (char in trimmed) {
                when {
                    char == '.' || char == ',' -> append(char)
                    isGroupingSpace(char) -> append(' ')
                    else -> append(char.digitToIntOrNull() ?: return null)
                }
            }
        }
        val dot = text.lastIndexOf('.')
        val comma = text.lastIndexOf(',')
        val lastSeparator = maxOf(dot, comma)
        if (lastSeparator == -1) return ungroup(text)

        val separator = text[lastSeparator]
        if (dot == -1 || comma == -1) {
            val repeated = text.indexOf(separator) != lastSeparator
            if (repeated) return ungroup(text)
            if (separator == groupingSeparator) ungroup(text)?.let { return it }
        }

        val integer = ungroup(text.substring(0, lastSeparator), decimal = separator) ?: return null
        val fraction = text.substring(lastSeparator + 1)
        if (!fraction.all(Char::isDigit)) return null
        return "$integer.$fraction"
    }

    private fun ungroup(integer: String, decimal: Char? = null): String? {
        val separator = integer.firstOrNull { !it.isDigit() } ?: return integer
        if (separator == decimal) return null
        val groups = integer.split(separator)
        if (groups.first().length !in 1..3 || groups.drop(1).any { it.length != 3 }) return null
        if (groups.any { !it.all(Char::isDigit) }) return null
        return groups.joinToString("")
    }
}

private fun isGroupingSpace(char: Char): Boolean = char == ' ' || char == '\u00a0' || char == '\u202f'
