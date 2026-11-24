package com.pocketfx.data.update

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class DownloadProgress(val bytes: Long, val total: Long?)

internal class UpdateHttp(private val source: UpdateSource, client: OkHttpClient = OkHttpClient()) {
    private val transport = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .addInterceptor(::redirects)
        .build()

    suspend fun manifest(): String = read(requireNotNull(source.manifest), isApk = false) { _, body ->
        val bytes = body.source()
        if (bytes.request(ManifestLimit + 1)) throw InvalidUpdateManifest()
        bytes.readUtf8()
    }

    suspend fun download(url: HttpUrl, destination: File, progress: (DownloadProgress) -> Unit): ByteArray =
        read(url, isApk = true) { call, body ->
            val total = body.contentLength().takeIf { it >= 0 }
            if (total == 0L || total != null && total > ApkLimit) throw IOException("Invalid APK length")
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            var lastProgress = 0L
            progress(DownloadProgress(bytes, total))
            Files.newOutputStream(destination.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                .buffered().use { output ->
                    val input = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (call.isCanceled()) throw IOException("Download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        if (bytes > ApkLimit) throw IOException("APK exceeds allowed size")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (bytes - lastProgress >= 256 * 1024) {
                            progress(DownloadProgress(bytes, total))
                            lastProgress = bytes
                        }
                    }
                }
            if (bytes == 0L || total != null && total != bytes) throw IOException("Incomplete APK")
            progress(DownloadProgress(bytes, total))
            digest.digest()
        }

    private fun redirects(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val isApk = request.tag(RequestKind::class.java) == RequestKind.APK
        if (isApk && !source.allowsAsset(request.url)) throw IOException("Untrusted APK source")
        repeat(6) { hop ->
            val response = chain.proceed(request)
            if (response.code !in RedirectCodes) return response
            val next = response.use { it.header("Location")?.let(request.url::resolve) }
            if (hop == 5 || next == null || !source.allowsRedirect(request.url, next, isApk)) {
                throw IOException("Untrusted update redirect")
            }
            request = request.newBuilder().url(next).build()
        }
        error("Redirect loop exceeded")
    }

    // Keep the call tied to the coroutine until consumption finishes, including the response body.
    private suspend fun <T> read(url: HttpUrl, isApk: Boolean, consume: (Call, ResponseBody) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .header("Accept", if (isApk) "application/octet-stream" else "application/json")
                .tag(RequestKind::class.java, if (isApk) RequestKind.APK else RequestKind.MANIFEST)
                .build()
            val call = transport.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw IOException("Update server HTTP ${it.code}")
                            consume(call, it.body ?: throw IOException("Empty update response"))
                        }
                        continuation.resume(result)
                    } catch (failure: Exception) {
                        continuation.resumeWithException(failure)
                    }
                }
            })
        }

    private enum class RequestKind { MANIFEST, APK }

    private companion object {
        const val ManifestLimit = 128 * 1024L
        const val ApkLimit = 512 * 1024 * 1024L
        val RedirectCodes = setOf(301, 302, 303, 307, 308)
    }
}
