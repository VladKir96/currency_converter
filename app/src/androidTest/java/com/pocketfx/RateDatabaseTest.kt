package com.pocketfx

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.pocketfx.data.local.HistoryCatalog
import com.pocketfx.data.local.MigrateRates1To2
import com.pocketfx.data.local.RateDatabase
import com.pocketfx.data.local.RoomHistoryStore
import com.pocketfx.data.local.RoomRateStore
import com.pocketfx.data.remote.ProviderId
import com.pocketfx.data.remote.PublishedRates
import com.pocketfx.data.remote.RateBase
import com.pocketfx.data.remote.StrictJson
import com.pocketfx.domain.HistoryPoint
import com.pocketfx.domain.HistoryQuery
import com.pocketfx.domain.HistorySeries
import com.pocketfx.domain.RateSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RateDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli()
    private val query = HistoryQuery.endingOn("EUR", "USD", 30, LocalDate.of(2026, 11, 18))

    @Test fun persistsSuccessfulChecksAcrossDatabaseReopens() = runBlocking(Dispatchers.IO) {
        val name = "rates-${UUID.randomUUID()}.db"
        val first = published(now - Duration.ofHours(3).toMillis(), now)
        val secondCheck = now + Duration.ofMinutes(11).toMillis()
        try {
            open(name).useDatabase { database ->
                val store = RoomRateStore(database)
                store.save(first)
                store.save(first.copy(snapshot = first.snapshot.copy(fetchedAt = secondCheck)))
            }
            open(name).useDatabase { database ->
                val store = RoomRateStore(database)
                val cached = store.latest.first()
                assertEquals(first.snapshot, cached?.snapshot)
                assertEquals(secondCheck, store.lastCheckedAt())
                assertEquals(secondCheck, cached?.lastCheckedAt)
                assertEquals(first.nextProviderUpdate, cached?.nextProviderUpdate)
                assertEquals(1, snapshotCount(database))
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun keepsOnlyTheLatestCurrentRateDatasetAndIgnoresOlderPublications() = runBlocking(Dispatchers.IO) {
        Room.inMemoryDatabaseBuilder(context, RateDatabase::class.java).build().useDatabase { database ->
            val store = RoomRateStore(database)
            val recent = now - 2000
            store.save(published(now - 10_000, now - 5000))
            store.save(published(recent, now))
            store.save(published(now - 3000, now + 5000))
            assertEquals(1, snapshotCount(database))
            assertEquals(recent, store.latest.first()?.snapshot?.providerUpdatedAt)
            assertEquals(now + 5000, store.latest.first()?.lastCheckedAt)
            assertEquals(recent + Duration.ofDays(1).toMillis(), store.latest.first()?.nextProviderUpdate)
        }
    }

    @Test fun migratesVersionOneWithoutLosingTheLatestOfflineRatesOrCheckMetadata() = runBlocking(Dispatchers.IO) {
        val name = "migration-${UUID.randomUUID()}.db"
        val latest = published(now - 1000, now - 500)
        try {
            createVersionOne(name)
            open(name).useDatabase { database ->
                val cached = RoomRateStore(database).latest.first()
                assertEquals(latest.snapshot, cached?.snapshot)
                assertEquals(now, cached?.lastCheckedAt)
                assertEquals(latest.nextProviderUpdate, cached?.nextProviderUpdate)
                assertEquals(1, snapshotCount(database))
                assertNull(RoomHistoryStore(database.history()).exact(query))
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun persistsIndependentFrankfurterRangesAndCatalogAcrossReopen() = runBlocking(Dispatchers.IO) {
        val name = "history-${UUID.randomUUID()}.db"
        val week = HistoryQuery.endingOn(query.source, query.target, 7, query.to)
        val points = listOf(HistoryPoint(query.to.minusDays(1), BigDecimal("1.1023456789123456")))
        val monthSeries = HistorySeries(query, now, points)
        val weekSeries = HistorySeries(week, now + 1000, points)
        val catalog = HistoryCatalog(now, listOf("EUR", "USD", "RSD", "RUB", "KPW"))
        try {
            open(name).useDatabase { database ->
                val history = RoomHistoryStore(database.history())
                history.save(monthSeries)
                history.save(weekSeries)
                history.saveCatalog(catalog)
            }
            open(name).useDatabase { database ->
                val history = RoomHistoryStore(database.history())
                assertEquals(monthSeries, history.exact(query))
                assertEquals(weekSeries, history.exact(week))
                assertEquals(catalog, history.catalog())
                assertEquals(weekSeries, history.earlier(week.copy(from = week.from.plusDays(1), to = week.to.plusDays(1))))
                assertNull(history.exact(query.copy(target = "RUB")))
                assertNull(RoomRateStore(database).latest.first())
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    private fun createVersionOne(name: String) {
        val asset = "${RateDatabase::class.java.name}/1.json"
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open(asset).bufferedReader().use {
            StrictJson.fromJson(it, JsonObject::class.java).getAsJsonObject("database")
        }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            for (entry in schema.getAsJsonArray("entities")) {
                val table = entry.asJsonObject
                val actualName = table.get("tableName").asString
                old.execSQL(table.get("createSql").asString.replace("\${TABLE_NAME}", actualName))
                table.getAsJsonArray("indices")?.forEach {
                    old.execSQL(it.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", actualName))
                }
            }
            schema.getAsJsonArray("setupQueries").forEach { old.execSQL(it.asString) }
            for (snapshot in listOf(published(now - 3000, now - 2500), published(now - 1000, now - 500))) {
                val rate = snapshot.snapshot
                old.execSQL(
                    "INSERT INTO rate_snapshots (provider, baseCurrency, providerUpdatedAt, fetchedAt, ratesJson) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        ProviderId, RateBase, rate.providerUpdatedAt, rate.fetchedAt,
                        StrictJson.toJson(rate.rates.mapValues { it.value.toString() }),
                    ),
                )
            }
            old.execSQL(
                "INSERT INTO rate_checks VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(ProviderId, RateBase, now, published(now - 1000, now).nextProviderUpdate),
            )
            old.version = 1
        }
    }

    private fun snapshotCount(database: RateDatabase): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM rate_snapshots").use {
            check(it.moveToFirst())
            it.getInt(0)
        }

    private inline fun <T> RateDatabase.useDatabase(block: (RateDatabase) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private fun open(name: String) = Room.databaseBuilder(context, RateDatabase::class.java, name)
        .addMigrations(MigrateRates1To2)
        .build()

    private fun published(publication: Long, fetched: Long) = PublishedRates(
        RateSnapshot(
            ProviderId, RateBase, publication, fetched,
            mapOf("USD" to BigDecimal.ONE, "EUR" to BigDecimal("0.81234567891234567890")),
        ),
        publication + Duration.ofDays(1).toMillis(),
    )
}
