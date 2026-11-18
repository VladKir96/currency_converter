package com.pocketfx.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.google.gson.reflect.TypeToken
import com.pocketfx.data.remote.FrankfurterProvider
import com.pocketfx.data.remote.StrictJson
import com.pocketfx.domain.HistoryPoint
import com.pocketfx.domain.HistoryQuery
import com.pocketfx.domain.HistorySeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Entity(tableName = "history_series", primaryKeys = ["provider", "sourceCurrency", "targetCurrency", "fromDay", "toDay"])
internal data class HistoryCacheEntity(
    val provider: String,
    val sourceCurrency: String,
    val targetCurrency: String,
    val fromDay: Long,
    val toDay: Long,
    val fetchedAt: Long,
    val pointsJson: String,
)

@Entity(tableName = "history_catalog", primaryKeys = ["provider"])
internal data class HistoryCatalogEntity(
    val provider: String,
    val fetchedAt: Long,
    val codesJson: String,
)

@Dao
internal interface HistoryDao {
    @Query("""
        SELECT * FROM history_series WHERE provider = :provider
        AND sourceCurrency = :source AND targetCurrency = :target
        AND fromDay = :from AND toDay = :to
    """)
    suspend fun exact(provider: String, source: String, target: String, from: Long, to: Long): HistoryCacheEntity?

    @Query("""
        SELECT * FROM history_series WHERE provider = :provider
        AND sourceCurrency = :source AND targetCurrency = :target
        AND toDay < :before AND fromDay = toDay - :span AND pointsJson != '{}'
        ORDER BY toDay DESC LIMIT 1
    """)
    suspend fun earlier(provider: String, source: String, target: String, span: Long, before: Long): HistoryCacheEntity?

    @Upsert
    suspend fun save(entry: HistoryCacheEntity)

    @Query("SELECT * FROM history_catalog WHERE provider = :provider")
    suspend fun catalog(provider: String): HistoryCatalogEntity?

    @Upsert
    suspend fun saveCatalog(catalog: HistoryCatalogEntity)
}

internal data class HistoryCatalog(val fetchedAt: Long, val codes: List<String>)

internal interface HistoryStore {
    suspend fun exact(query: HistoryQuery): HistorySeries?
    suspend fun earlier(query: HistoryQuery): HistorySeries?
    suspend fun save(series: HistorySeries)
    suspend fun catalog(): HistoryCatalog?
    suspend fun saveCatalog(catalog: HistoryCatalog)
}

internal class RoomHistoryStore(private val dao: HistoryDao) : HistoryStore {
    override suspend fun exact(query: HistoryQuery): HistorySeries? = withContext(Dispatchers.Default) {
        dao.exact(FrankfurterProvider, query.source, query.target, query.from.toEpochDay(), query.to.toEpochDay())?.toSeries()
    }

    override suspend fun earlier(query: HistoryQuery): HistorySeries? = withContext(Dispatchers.Default) {
        val end = query.to.toEpochDay()
        dao.earlier(FrankfurterProvider, query.source, query.target, end - query.from.toEpochDay(), end)?.toSeries()
    }

    override suspend fun save(series: HistorySeries) = withContext(Dispatchers.Default) {
        val query = series.query
        val points = series.points.associate { it.date.toString() to it.rate.toString() }
        dao.save(
            HistoryCacheEntity(
                FrankfurterProvider, query.source, query.target, query.from.toEpochDay(), query.to.toEpochDay(),
                series.fetchedAt, StrictJson.toJson(points),
            )
        )
    }

    override suspend fun catalog(): HistoryCatalog? = withContext(Dispatchers.Default) {
        dao.catalog(FrankfurterProvider)?.let {
            HistoryCatalog(it.fetchedAt, StrictJson.fromJson(it.codesJson, CodeList))
        }
    }

    override suspend fun saveCatalog(catalog: HistoryCatalog) = withContext(Dispatchers.Default) {
        dao.saveCatalog(HistoryCatalogEntity(FrankfurterProvider, catalog.fetchedAt, StrictJson.toJson(catalog.codes)))
    }

    private fun HistoryCacheEntity.toSeries(): HistorySeries {
        val query = HistoryQuery(sourceCurrency, targetCurrency, LocalDate.ofEpochDay(fromDay), LocalDate.ofEpochDay(toDay))
        val points = StrictJson.fromJson(pointsJson, PointMap)
            .map { (date, rate) -> HistoryPoint(LocalDate.parse(date), rate.toBigDecimal()) }
            .sortedBy(HistoryPoint::date)
        return HistorySeries(query, fetchedAt, points)
    }

    private companion object {
        val PointMap = object : TypeToken<Map<String, String>>() {}
        val CodeList = object : TypeToken<List<String>>() {}
    }
}
