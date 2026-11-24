package com.pocketfx.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

internal data class SavedCheck(val timestamp: Long, val manifest: String)
internal data class UpdateCheck(val timestamp: Long, val available: UpdateInfo?)

internal interface UpdateCheckStore {
    suspend fun read(source: String): SavedCheck?
    suspend fun write(source: String, check: SavedCheck)
}

internal class DataStoreUpdateChecks(private val store: DataStore<Preferences>) : UpdateCheckStore {
    override suspend fun read(source: String): SavedCheck? {
        val prefs = store.data.first()
        if (prefs[Feed] != source) return null
        return SavedCheck(prefs[Timestamp] ?: return null, prefs[Manifest] ?: return null)
    }

    override suspend fun write(source: String, check: SavedCheck) {
        store.edit {
            it[Feed] = source
            it[Timestamp] = check.timestamp
            it[Manifest] = check.manifest
        }
    }

    private companion object {
        val Feed = stringPreferencesKey("update_feed")
        val Manifest = stringPreferencesKey("update_manifest")
        val Timestamp = longPreferencesKey("update_checked_at")
    }
}

internal class InvalidUpdateApk : IOException()

internal class UpdateRepository(
    val installed: AppVersion,
    private val source: UpdateSource,
    private val http: UpdateHttp,
    private val store: UpdateCheckStore,
    private val directory: File,
    private val inspectApk: (File) -> AppVersion?,
    private val clock: Clock,
) {
    val configured: Boolean get() = source.manifest != null
    private val checkLock = Mutex()
    private val downloadLock = Mutex()

    suspend fun check(force: Boolean, onCached: (UpdateCheck) -> Unit = {}): UpdateCheck? = checkLock.withLock {
        if (!configured) return@withLock null
        val cached = try {
            store.read(source.cacheKey)?.let { withContext(Dispatchers.Default) { it.toCheck() } }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
        if (cached != null) {
            onCached(cached)
            if (!force && clock.millis() - cached.timestamp in 0 until UpdateInterval) return@withLock cached
        }
        val raw = http.manifest()
        val saved = SavedCheck(clock.millis(), raw)
        val checked = withContext(Dispatchers.Default) { saved.toCheck() }
        store.write(source.cacheKey, saved)
        checked
    }

    suspend fun download(info: UpdateInfo, progress: (DownloadProgress) -> Unit): File = downloadLock.withLock {
        withContext(Dispatchers.IO) {
            if (info.version.packageName != installed.packageName || info.version.code <= installed.code ||
                !source.allowsAsset(info.apk)
            ) throw InvalidUpdateApk()
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update directory")
            directory.listFiles()?.filter { it.isFile && (it.name.startsWith("partial-") || it.name.startsWith("pocket-fx-")) }
                ?.forEach { it.delete() }
            val partial = File.createTempFile("partial-", ".apk", directory)
            try {
                val digest = http.download(info.apk, partial, progress)
                ensureActive()
                val expected = info.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (!MessageDigest.isEqual(digest, expected)) throw InvalidUpdateApk()
                val apk = inspectApk(partial)
                if (apk == null || apk.packageName != installed.packageName || apk.code != info.version.code ||
                    apk.code <= installed.code
                ) throw InvalidUpdateApk()
                val verified = directory.resolve("pocket-fx-${info.version.code}.apk")
                ensureActive()
                Files.move(partial.toPath(), verified.toPath(), StandardCopyOption.REPLACE_EXISTING)
                verified
            } finally {
                partial.delete()
            }
        }
    }

    private fun SavedCheck.toCheck(): UpdateCheck {
        val info = parseUpdateManifest(manifest, installed.packageName, source)
        return UpdateCheck(timestamp, info.takeIf { it.version.code > installed.code })
    }

    private companion object {
        val UpdateInterval = Duration.ofHours(24).toMillis()
    }
}
