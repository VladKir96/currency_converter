package com.pocketfx

import com.google.gson.JsonObject
import com.pocketfx.data.update.AppVersion
import com.pocketfx.data.update.DownloadProgress
import com.pocketfx.data.update.InvalidUpdateApk
import com.pocketfx.data.update.InvalidUpdateManifest
import com.pocketfx.data.update.SavedCheck
import com.pocketfx.data.update.UpdateCheckStore
import com.pocketfx.data.update.UpdateHttp
import com.pocketfx.data.update.UpdateInfo
import com.pocketfx.data.update.UpdateRepository
import com.pocketfx.data.update.UpdateSource
import com.pocketfx.data.update.parseUpdateManifest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit

class UpdateRepositoryTest {
    @get:Rule val files = TemporaryFolder()
    private val server = MockWebServer()
    private val installed = AppVersion("com.pocketfx", 4, "1.3.0")
    private val candidate = AppVersion(installed.packageName, 5, "1.4.0")
    private val bytes = ByteArray(600_000) { (it % 251).toByte() }
    private val clock = MutableClock(Instant.parse("2026-11-23T18:00:00Z"))
    private val memory = MemoryChecks()
    private lateinit var client: OkHttpClient
    private lateinit var source: UpdateSource
    private var archive: AppVersion? = candidate

    @Before fun startServer() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        source = UpdateSource(server.url("/manifest.json").toString())
    }

    @After fun stopServer() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test fun versionCodeAloneDeterminesUpdateAvailability() = runBlocking {
        val repository = repository()
        for (code in listOf(5L, 4L, 3L)) {
            server.enqueue(MockResponse().setBody(manifest(code, name = if (code == 5L) "0.1" else "99.0").toString()))
            val result = requireNotNull(repository.check(force = true))
            assertEquals(code == 5L, result.available != null)
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun validatesManifestIdentityFormatAndOrigin() {
        val invalid = listOf(
            manifest().apply { addProperty("versionCode", "5") },
            manifest().apply { addProperty("versionCode", -5) },
            manifest().apply { addProperty("versionCode", 5.1) },
            manifest().apply { remove("versionName") },
            manifest().apply { addProperty("packageName", "other.app") },
            manifest().apply { remove("apkUrl") },
            manifest().apply { addProperty("apkUrl", "http://localhost/update.apk") },
            manifest().apply { addProperty("apkUrl", "https://untrusted.example/update.apk") },
            manifest().apply { addProperty("sha256", "invalid") },
        )
        invalid.forEach { value ->
            assertThrows(InvalidUpdateManifest::class.java) {
                parseUpdateManifest(value.toString(), installed.packageName, source)
            }
        }
        val update = info(manifest().apply {
            add("releaseNotes", JsonObject().apply {
                addProperty("en", "English")
                addProperty("sr-Latn", "Srpski")
            })
        })
        assertEquals("Srpski", update.notesFor(Locale.forLanguageTag("sr-Latn-RS")))
        assertEquals("English", update.notesFor(Locale.forLanguageTag("ru-RU")))
    }

    @Test fun disabledSourceDoesNotFetchAndGitHubRedirectPolicyStaysOnTrustedHosts() = runBlocking {
        val disabled = UpdateSource("")
        assertNull(repository(disabled).check(force = true))
        assertEquals(0, server.requestCount)
        val github = "https://github.com/owner/repo/releases/download/v1/app.apk".toHttpUrl()
        val cdn = "https://release-assets.githubusercontent.com/path".toHttpUrl()
        assertTrue(source.allowsAsset(github))
        assertTrue(source.allowsRedirect(github, cdn, isApk = true))
        assertFalse(source.allowsAsset(cdn))
        assertFalse(source.allowsRedirect(github, "http://github.com/owner/repo/releases/download/v1/app.apk".toHttpUrl(), true))
        assertFalse(source.allowsRedirect(github, "https://other.example/app.apk".toHttpUrl(), true))
        assertFalse(source.allowsAsset("https://github.com/owner/repo/blob/main/app.apk".toHttpUrl()))
    }

    @Test fun updateCheckPersistsFor24HoursAndManualCheckBypassesIt() = runBlocking {
        server.enqueue(MockResponse().setBody(manifest().toString()))
        val first = requireNotNull(repository().check(force = false))
        clock.advance(86_399)
        assertEquals(first, repository().check(force = false))
        assertEquals(1, server.requestCount)
        clock.advance(1)
        server.enqueue(MockResponse().setBody(manifest(6).toString()))
        val second = requireNotNull(repository().check(force = false))
        assertEquals(6L, second.available?.version?.code)
        server.enqueue(MockResponse().setBody(manifest(7).toString()))
        assertEquals(7L, repository().check(force = true)?.available?.version?.code)
        assertEquals(3, server.requestCount)
    }

    @Test fun failedNetworkCheckKeepsTheLastSuccessfulTimestamp() = runBlocking {
        server.enqueue(MockResponse().setBody(manifest().toString()))
        val first = requireNotNull(repository().check(force = false))
        server.enqueue(MockResponse().setResponseCode(503))
        clock.advance(86_400)
        val outcome = runCatching { repository().check(force = false) }
        assertTrue(outcome.exceptionOrNull() is IOException)
        assertEquals(first.timestamp, memory.read(source.cacheKey)?.timestamp)
    }

    @Test fun successfulDownloadStreamsReportsProgressAndPublishesTheVerifiedFile() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/file.apk"))
        server.enqueue(apkResponse())
        val progress = mutableListOf<DownloadProgress>()
        val file = repository().download(info(), progress::add)
        assertEquals("pocket-fx-5.apk", file.name)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(DownloadProgress(0, bytes.size.toLong()), progress.first())
        assertEquals(DownloadProgress(bytes.size.toLong(), bytes.size.toLong()), progress.last())
        assertTrue(progress.any { it.bytes in 1 until bytes.size.toLong() })
        assertEquals(listOf(file), files.root.listFiles()?.toList())
    }

    @Test fun redirectsCannotDowngradeToHttp() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://localhost/file.apk"))
        assertTrue(runCatching { repository().download(info()) {} }.exceptionOrNull() is IOException)
        assertEquals(1, server.requestCount)
        assertTrue(files.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun checksumPackageAndVersionMismatchRejectTheFile() = runBlocking {
        server.enqueue(apkResponse())
        val invalidChecksum = info(manifest().apply { addProperty("sha256", "0".repeat(64)) })
        assertTrue(runCatching { repository().download(invalidChecksum) {} }.exceptionOrNull() is InvalidUpdateApk)
        for (wrong in listOf(null, candidate.copy(packageName = "other.app"), candidate.copy(code = 4), candidate.copy(code = 6))) {
            archive = wrong
            server.enqueue(apkResponse())
            assertTrue(runCatching { repository().download(info()) {} }.exceptionOrNull() is InvalidUpdateApk)
            assertTrue(files.root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun interruptedDownloadDeletesPartialFile() = runBlocking {
        server.enqueue(apkResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertTrue(runCatching { repository().download(info()) {} }.exceptionOrNull() is IOException)
        assertTrue(files.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellingActiveDownloadDeletesPartialFile() = runBlocking {
        server.enqueue(apkResponse().throttleBody(64L * 1024, 300, TimeUnit.MILLISECONDS))
        val started = CompletableDeferred<Unit>()
        val job = async { repository().download(info()) { if (it.bytes > 0) started.complete(Unit) } }
        withTimeout(8_000) { started.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(files.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun checksFromADifferentFeedDoNotReuseAnotherFeedsTimestamp() = runBlocking {
        server.enqueue(MockResponse().setBody(manifest().toString()))
        assertNotNull(repository().check(force = false))
        val different = UpdateSource(server.url("/other.json").toString())
        server.enqueue(MockResponse().setBody(manifest().toString()))
        assertNotNull(repository(different).check(force = false))
        assertEquals(2, server.requestCount)
    }

    private fun repository(feed: UpdateSource = source) =
        UpdateRepository(installed, feed, UpdateHttp(feed, client), memory, files.root, { archive }, clock)

    private fun manifest(code: Long = 5L, name: String = "1.4.0") = JsonObject().apply {
        addProperty("versionCode", code)
        addProperty("versionName", name)
        addProperty("packageName", installed.packageName)
        addProperty("apkUrl", server.url("/update.apk").toString())
        addProperty("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }

    private fun info(json: JsonObject = manifest()): UpdateInfo = parseUpdateManifest(json.toString(), installed.packageName, source)
    private fun apkResponse() = MockResponse().setBody(Buffer().write(bytes))

    private class MutableClock(private var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(instant, zone)
        override fun instant(): Instant = instant
        fun advance(seconds: Long) { instant = instant.plusSeconds(seconds) }
    }

    private class MemoryChecks : UpdateCheckStore {
        private val records = mutableMapOf<String, SavedCheck>()
        override suspend fun read(source: String) = records[source]
        override suspend fun write(source: String, check: SavedCheck) { records[source] = check }
    }
}
