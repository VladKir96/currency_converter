package com.pocketfx

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketfx.data.local.RateDatabase
import com.pocketfx.data.local.RoomRateStore
import com.pocketfx.data.remote.ProviderId
import com.pocketfx.data.remote.PublishedRates
import com.pocketfx.data.remote.RateBase
import com.pocketfx.domain.HistoryRetention
import com.pocketfx.domain.RateSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RateDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-05-18T12:00:00Z").toEpochMilli()

    @Test fun persistsChecksAndDeduplicatesSnapshotsAcrossDatabaseReopens() = runBlocking {
        val name = "rates-${UUID.randomUUID()}.db"
        val publication = now - Duration.ofHours(3).toMillis()
        val first = published(publication, now)
        val secondCheck = now + Duration.ofMinutes(11).toMillis()
        try {
            open(name).useDatabase { database ->
                val store = RoomRateStore(database)
                store.save(first)
                store.save(first.copy(snapshot = first.snapshot.copy(fetchedAt = secondCheck)))
            }
            open(name).useDatabase { database ->
                val store = RoomRateStore(database)
                assertEquals(secondCheck, store.lastCheckedAt())
                assertEquals(listOf(first.snapshot), store.history(0).first())
                val cached = store.latest.first()
                assertEquals(first.snapshot, cached?.snapshot)
                assertEquals(secondCheck, cached?.lastCheckedAt)
                assertEquals(first.nextProviderUpdate, cached?.nextProviderUpdate)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun prunesOldHistoryAndDoesNotReplaceLatestWithEarlierPublication() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, RateDatabase::class.java).build().useDatabase { database ->
            val store = RoomRateStore(database)
            val expired = now - HistoryRetention.toMillis() - 1000
            val recent = now - 2000
            val previous = now - 3000
            store.save(published(expired, expired))
            assertNotNull(store.latest.first())
            store.save(published(recent, now))
            store.save(published(previous, now + 5000))
            assertEquals(listOf(previous, recent), store.history(0).first().map { it.providerUpdatedAt })
            assertEquals(recent, store.latest.first()?.snapshot?.providerUpdatedAt)
            assertEquals(now + 5000, store.latest.first()?.lastCheckedAt)
            assertEquals(recent + Duration.ofDays(1).toMillis(), store.latest.first()?.nextProviderUpdate)
        }
    }

    private inline fun <T> RateDatabase.useDatabase(block: (RateDatabase) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private fun open(name: String) = Room.databaseBuilder(context, RateDatabase::class.java, name).build()

    private fun published(publication: Long, fetched: Long) = PublishedRates(
        RateSnapshot(
            ProviderId, RateBase, publication, fetched,
            mapOf("USD" to BigDecimal.ONE, "EUR" to BigDecimal("0.81234567891234567890")),
        ),
        publication + Duration.ofDays(1).toMillis(),
    )
}
