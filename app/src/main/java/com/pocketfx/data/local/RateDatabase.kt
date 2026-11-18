package com.pocketfx.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "rate_snapshots",
    indices = [Index(value = ["provider", "baseCurrency", "providerUpdatedAt"], unique = true)],
)
internal data class RateSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val baseCurrency: String,
    val providerUpdatedAt: Long,
    val fetchedAt: Long,
    val ratesJson: String,
)

@Entity(tableName = "rate_checks", primaryKeys = ["provider", "baseCurrency"])
internal data class RateCheckEntity(
    val provider: String,
    val baseCurrency: String,
    val lastCheckedAt: Long,
    val nextProviderUpdate: Long?,
)

internal data class LatestRatesRow(
    @Embedded val snapshot: RateSnapshotEntity,
    val lastCheckedAt: Long,
    val nextProviderUpdate: Long?,
)

@Dao
internal interface RateDao {
    @Query("""
        SELECT s.*, c.lastCheckedAt, c.nextProviderUpdate
        FROM rate_snapshots AS s
        INNER JOIN rate_checks AS c ON s.provider = c.provider AND s.baseCurrency = c.baseCurrency
        WHERE s.provider = :provider AND s.baseCurrency = :base
        ORDER BY s.providerUpdatedAt DESC LIMIT 1
    """)
    fun observeLatest(provider: String, base: String): Flow<LatestRatesRow?>

    @Query("""
        SELECT * FROM rate_snapshots WHERE provider = :provider AND baseCurrency = :base
        AND providerUpdatedAt >= :since ORDER BY providerUpdatedAt
    """)
    fun observeHistory(provider: String, base: String, since: Long): Flow<List<RateSnapshotEntity>>

    @Query("SELECT * FROM rate_checks WHERE provider = :provider AND baseCurrency = :base")
    suspend fun getCheck(provider: String, base: String): RateCheckEntity?

    @Query("""
        SELECT MAX(providerUpdatedAt) FROM rate_snapshots
        WHERE provider = :provider AND baseCurrency = :base
    """)
    suspend fun latestPublication(provider: String, base: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(snapshot: RateSnapshotEntity)

    @Upsert
    suspend fun recordCheck(check: RateCheckEntity)

    @Query("""
        DELETE FROM rate_snapshots WHERE provider = :provider AND baseCurrency = :base
        AND providerUpdatedAt < :cutoff AND providerUpdatedAt < :latestPublication
    """)
    suspend fun prune(provider: String, base: String, cutoff: Long, latestPublication: Long)
}

@Database(entities = [RateSnapshotEntity::class, RateCheckEntity::class], version = 1)
internal abstract class RateDatabase : RoomDatabase() {
    abstract fun rates(): RateDao
}
