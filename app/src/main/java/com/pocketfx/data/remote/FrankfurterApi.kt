package com.pocketfx.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonParseException
import com.pocketfx.domain.HistoryPoint
import com.pocketfx.domain.HistoryQuery
import com.pocketfx.domain.isCurrencyCode
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal const val FrankfurterProvider = "frankfurter-v2"

internal interface FrankfurterApi {
    @GET("v2/rates")
    suspend fun rates(
        @Query("base") base: String,
        @Query("quotes") quote: String,
        @Query("from") from: String,
        @Query("to") to: String,
    ): Response<ResponseBody>

    @GET("v2/currencies")
    suspend fun currencies(): Response<ResponseBody>
}

internal fun frankfurterApi(
    endpoint: HttpUrl = "https://api.frankfurter.dev/".toHttpUrl(),
    client: OkHttpClient = rateHttpClient(),
): FrankfurterApi {
    require(endpoint.isHttps)
    return Retrofit.Builder().baseUrl(endpoint).client(client).build().create(FrankfurterApi::class.java)
}

internal fun ResponseBody.readHistory(query: HistoryQuery): List<HistoryPoint> {
    val points = sortedMapOf<LocalDate, HistoryPoint>()
    for (element in readArray()) {
        if (!element.isJsonObject) throw JsonParseException("Invalid historical observation")
        val row = element.asJsonObject
        if (row.string("base") != query.source || row.string("quote") != query.target) {
            throw JsonParseException("Unexpected historical currency pair")
        }
        val date = try {
            LocalDate.parse(row.string("date") ?: throw JsonParseException("Missing historical date"))
        } catch (error: DateTimeParseException) {
            throw JsonParseException("Invalid historical date", error)
        }
        if (date < query.from || date > query.to) continue
        val rate = row.get("rate")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asString?.takeIf { it.length <= 100 }?.toBigDecimalOrNull()
            ?.takeIf { it.signum() > 0 && it.scale() in -30..60 }
            ?: throw JsonParseException("Invalid historical rate")
        val previous = points.putIfAbsent(date, HistoryPoint(date, rate))
        if (previous != null && previous.rate.compareTo(rate) != 0) {
            throw JsonParseException("Conflicting observations for one date")
        }
    }
    return points.values.toList()
}

internal fun ResponseBody.readHistoryCurrencies(): List<String> {
    val codes = readArray().mapNotNull { element ->
        val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        row.string("iso_code")?.takeIf { row.string("type") == "currency" && isCurrencyCode(it) }
    }.distinct().sorted()
    if (codes.isEmpty()) throw JsonParseException("Empty historical currency catalog")
    return codes
}

private fun ResponseBody.readArray(): JsonArray =
    StrictJson.fromJson(charStream(), JsonArray::class.java) ?: throw JsonParseException("Missing JSON array")
