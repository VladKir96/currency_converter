package com.pocketfx.domain

import java.math.BigDecimal

private val CustomRatePattern = Regex("[0-9]{0,19}(\\.[0-9]{0,52})?")
private val MinCustomRate = BigDecimal("1E-18")
private val MaxCustomRate = BigDecimal("1E18")

internal fun normalizeCustomRate(input: String): String? {
    if (input.length > 72) return null
    return canonicalDecimal(input)?.takeIf(CustomRatePattern::matches)
}

internal fun parseCustomRate(input: String): BigDecimal? =
    normalizeCustomRate(input)?.toBigDecimalOrNull()?.takeIf { it in MinCustomRate..MaxCustomRate }

internal data class CurrencyPair(val first: String, val second: String) {
    init {
        require(isCurrencyCode(first) && isCurrencyCode(second) && first < second)
    }

    companion object {
        fun of(source: String, target: String): CurrencyPair =
            if (source < target) CurrencyPair(source, target) else CurrencyPair(target, source)
    }
}

internal data class CustomExchangeRate(val source: String, val target: String, val rate: BigDecimal) {
    val pair = CurrencyPair.of(source, target)

    init {
        require(rate in MinCustomRate..MaxCustomRate && rate.scale() <= 52)
    }

    fun from(currency: String): BigDecimal =
        if (currency == source) rate else BigDecimal.ONE.divide(rate, RatePrecision)
}

internal data class ResolvedExchangeRate(private val api: ApiCrossRate?, val customRate: BigDecimal?) {
    val apiRate: BigDecimal? get() = api?.value
    val effectiveRate: BigDecimal? get() = customRate ?: apiRate
    val isCustom: Boolean get() = customRate != null

    fun convert(amount: BigDecimal): BigDecimal? {
        if (amount.signum() < 0) return null
        return customRate?.let(amount::multiply) ?: api?.convert(amount)
    }
}

internal fun resolveExchangeRate(
    source: String,
    target: String,
    usdRates: Map<String, BigDecimal>,
    customRates: Map<CurrencyPair, CustomExchangeRate>,
): ResolvedExchangeRate = ResolvedExchangeRate(
    api = apiCrossRate(source, target, usdRates),
    customRate = if (source == target) null else customRates[CurrencyPair.of(source, target)]?.from(source),
)
