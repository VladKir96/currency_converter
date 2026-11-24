package com.pocketfx.data.update

import com.google.gson.JsonObject
import com.pocketfx.data.remote.StrictJson
import com.pocketfx.data.remote.string
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Locale

internal data class AppVersion(val packageName: String, val code: Long, val name: String)

internal data class UpdateInfo(
    val version: AppVersion,
    val apk: HttpUrl,
    val sha256: String,
    val notes: Map<String, String>,
) {
    fun notesFor(locale: Locale): String? = notes[locale.stripExtensions().toLanguageTag()]
        ?: if (locale.language == "sr") notes["sr-Latn"] ?: notes["en"] else notes[locale.language] ?: notes["en"]
}

internal class UpdateSource(manifest: String, apkOrigin: String = "") {
    val manifest = manifest.httpsUrl()
    private val additionalOrigin = apkOrigin.httpsUrl()?.takeIf { it.encodedPath == "/" && it.query == null }
    val cacheKey = "${this.manifest.orEmptyUrl()}|${additionalOrigin.orEmptyUrl()}"

    fun allowsAsset(url: HttpUrl): Boolean =
        url.secure() && (url.githubRelease() || url.host != "github.com" &&
            (manifest?.sameOrigin(url) == true || additionalOrigin?.sameOrigin(url) == true))

    fun allowsRedirect(from: HttpUrl, to: HttpUrl, isApk: Boolean): Boolean {
        if (!to.secure()) return false
        if ((from.githubRelease() || from.githubReleaseCdn()) && to.githubReleaseCdn()) return true
        if (!from.sameOrigin(to)) return false
        return if (isApk) allowsAsset(to) else {
            manifest?.sameOrigin(to) == true && (!from.githubRelease() || to.githubRelease())
        }
    }
}

private fun String.httpsUrl(): HttpUrl? = toHttpUrlOrNull()?.takeIf { it.secure() }
private fun HttpUrl?.orEmptyUrl(): String = this?.toString().orEmpty()
private fun HttpUrl.secure() = isHttps && username.isEmpty() && password.isEmpty() && fragment == null
private fun HttpUrl.sameOrigin(other: HttpUrl) = scheme == other.scheme && host == other.host && port == other.port

private fun HttpUrl.githubRelease(): Boolean {
    if (host != "github.com" || port != 443 || pathSegments.size < 5 || pathSegments[2] != "releases") return false
    return pathSegments[3] == "download" && pathSegments.size >= 6 ||
        pathSegments[3] == "latest" && pathSegments[4] == "download" && pathSegments.size >= 6
}

private fun HttpUrl.githubReleaseCdn() = port == 443 && host in GithubReleaseCdn
private val GithubReleaseCdn = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")

internal class InvalidUpdateManifest : IOException()

internal fun parseUpdateManifest(raw: String, packageName: String, source: UpdateSource): UpdateInfo {
    val json = StrictJson.fromJson(raw, JsonObject::class.java) ?: throw InvalidUpdateManifest()
    val versionCode = json.get("versionCode")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asString?.toLongOrNull()?.takeIf { it > 0 } ?: throw InvalidUpdateManifest()
    val versionName = json.string("versionName")?.trim()?.takeIf {
        it.length in 1..80 && it.none(Char::isISOControl)
    } ?: throw InvalidUpdateManifest()
    if (json.string("packageName") != packageName) throw InvalidUpdateManifest()
    val apk = json.string("apkUrl")?.httpsUrl()?.takeIf(source::allowsAsset) ?: throw InvalidUpdateManifest()
    val sha256 = json.string("sha256")?.takeIf { it.matches(ChecksumPattern) } ?: throw InvalidUpdateManifest()
    val notes = json.get("releaseNotes")?.let { element ->
        if (!element.isJsonObject) throw InvalidUpdateManifest()
        val items = element.asJsonObject
        SupportedNotes.mapNotNull { language ->
            if (!items.has(language)) return@mapNotNull null
            val note = items.string(language)?.takeIf { it.length <= 8_000 } ?: throw InvalidUpdateManifest()
            note.trim().takeIf(String::isNotEmpty)?.let { language to it }
        }.toMap()
    }.orEmpty()
    return UpdateInfo(AppVersion(packageName, versionCode, versionName), apk, sha256.lowercase(Locale.ROOT), notes)
}

private val ChecksumPattern = Regex("[0-9a-fA-F]{64}")
private val SupportedNotes = listOf("en", "ru", "sr-Latn")
