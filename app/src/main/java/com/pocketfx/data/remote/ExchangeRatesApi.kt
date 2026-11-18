package com.pocketfx.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.pocketfx.domain.RateSnapshot
import com.pocketfx.domain.isCurrencyCode
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import java.math.BigDecimal
import java.time.Duration

internal const val ProviderId = "exchange-rate-api"
internal const val RateBase = "USD"

internal val StrictJson: Gson = GsonBuilder().setStrictness(Strictness.STRICT).create()

internal interface ExchangeRatesApi {
    @GET("v6/latest/USD")
    suspend fun latest(): Response<ResponseBody>
}

internal fun exchangeRatesApi(
    endpoint: HttpUrl = "https://open.er-api.com/".toHttpUrl(),
    client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(Duration.ofSeconds(20))
        .connectTimeout(Duration.ofSeconds(8))
        .readTimeout(Duration.ofSeconds(12))
        .retryOnConnectionFailure(false)
        .build(),
): ExchangeRatesApi {
    require(endpoint.isHttps)
    return Retrofit.Builder()
        .baseUrl(endpoint)
        .client(client)
        .build()
        .create(ExchangeRatesApi::class.java)
}

internal class InvalidRatesException : Exception()

internal data class PublishedRates(
    val snapshot: RateSnapshot,
    val nextProviderUpdate: Long?,
)

private fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.time(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asString?.toLongOrNull()
        ?.takeIf { it in 1..253_402_300_799L }
        ?.times(1000)

internal fun ResponseBody.toPublishedRates(fetchedAt: Long): PublishedRates =
    use {
        val payload = StrictJson.fromJson(it.charStream(), JsonObject::class.java) ?: throw InvalidRatesException()
        payload.toPublishedRates(fetchedAt)
    }

private fun JsonObject.toPublishedRates(fetchedAt: Long): PublishedRates {
    if (string("result") != "success" || string("base_code") != RateBase) throw InvalidRatesException()
    val publication = time("time_last_update_unix") ?: throw InvalidRatesException()
    val payload = get("rates")?.takeIf { it.isJsonObject }?.asJsonObject ?: throw InvalidRatesException()
    val rates = buildMap {
        for ((code, value) in payload.entrySet()) {
            if (!isCurrencyCode(code) || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) continue
            val decimal = value.asString.takeIf { it.length <= 100 }?.toBigDecimalOrNull() ?: continue
            if (decimal.signum() > 0 && decimal.scale() in -30..60) put(code, decimal)
        }
    }
    if (rates.size < 2 || rates[RateBase]?.compareTo(BigDecimal.ONE) != 0) throw InvalidRatesException()
    return PublishedRates(
        RateSnapshot(ProviderId, RateBase, publication, fetchedAt, rates),
        time("time_next_update_unix")?.takeIf { it > publication },
    )
}
