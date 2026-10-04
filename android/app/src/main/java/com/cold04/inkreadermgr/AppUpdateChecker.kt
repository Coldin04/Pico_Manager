package com.cold04.inkreadermgr

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class AppRelease(
    val versionName: String,
    val releasePage: String,
    val notes: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long?,
    val apkArchitecture: String,
    val apkSha256: String,
)

internal data class AppUpdateCheck(
    val currentVersion: String,
    val latestRelease: AppRelease?,
    val updateAvailable: Boolean,
)

internal object AppUpdateChecker {
    private const val RELEASES_URL =
        "https://api.github.com/repos/Coldin04/Pico_Manager/releases?per_page=100"
    private const val RELEASE_PREFIX = "Android-v"
    private val versionPattern = Regex(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-preview([1-9][0-9]*)?)?$",
    )

    fun compareVersions(left: String, right: String): Int {
        val leftVersion = AppVersion.parse(left) ?: return -1
        val rightVersion = AppVersion.parse(right) ?: return -1
        return leftVersion.compareTo(rightVersion)
    }

    suspend fun check(currentVersion: String, includePreviews: Boolean): AppUpdateCheck =
        withContext(Dispatchers.IO) {
            val current = AppVersion.parse(currentVersion)
                ?: throw IOException("当前 App 版本格式无法识别")
            val releases = fetchReleases()
            val latestCandidate = (0 until releases.length())
                .mapNotNull { index -> parseRelease(releases.optJSONObject(index), includePreviews) }
                .maxByOrNull { it.version }
            val latestRelease = latestCandidate?.takeIf { it.version > current }?.let { candidate ->
                AppRelease(
                    versionName = candidate.versionName,
                    releasePage = candidate.releasePage,
                    notes = candidate.notes,
                    apkDownloadUrl = candidate.apkDownloadUrl,
                    apkSizeBytes = candidate.apkSizeBytes,
                    apkArchitecture = candidate.apkArchitecture,
                    apkSha256 = resolveSha256(candidate),
                )
            }
            AppUpdateCheck(
                currentVersion = current.toString(),
                latestRelease = latestRelease,
                updateAvailable = latestRelease != null,
            )
        }

    private fun fetchReleases(): JSONArray {
        val connection = URL(RELEASES_URL).openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "PicoManager-Android")
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("GitHub 返回 HTTP $responseCode")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONArray(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(
        release: org.json.JSONObject?,
        includePreviews: Boolean,
    ): ReleaseCandidate? {
        if (release == null || release.optBoolean("draft", true)) return null
        val tag = release.optString("tag_name")
        if (!tag.startsWith(RELEASE_PREFIX)) return null
        val versionName = tag.removePrefix(RELEASE_PREFIX)
        val version = AppVersion.parse(versionName) ?: return null
        val isPreview = release.optBoolean("prerelease", false) || version.previewNumber != null
        if (isPreview && !includePreviews) return null
        val assets = release.optJSONArray("assets") ?: return null
        val apkAssets = (0 until assets.length()).mapNotNull { index -> assets.optJSONObject(index) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        fun apkFor(abi: String) = apkAssets.firstOrNull {
            it.optString("name").equals("app-$abi-release.apk", ignoreCase = true)
        } ?: apkAssets.firstOrNull {
            it.optString("name").endsWith("-$abi.apk", ignoreCase = true)
        }
        val abiAsset = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            apkFor(abi)?.let { abi to it }
        }
        val selectedAsset = abiAsset?.second ?: apkFor("universal") ?: return null
        val architecture = abiAsset?.first ?: "universal"
        val assetName = selectedAsset.optString("name")
        val downloadUrl = selectedAsset.optString("browser_download_url")
            .takeIf { it.startsWith("https://github.com/") }
            ?: return null
        val checksumsUrl = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name").equals("SHA256SUMS.txt", ignoreCase = true) }
            ?.optString("browser_download_url")
            ?.takeIf { it.startsWith("https://github.com/") }

        val page = release.optString("html_url").takeIf { it.startsWith("https://github.com/") }
            ?: return null
        return ReleaseCandidate(
            versionName = versionName,
            version = version,
            releasePage = page,
            notes = release.optString("body").takeUnless { it == "null" }.orEmpty(),
            apkDownloadUrl = downloadUrl,
            apkSizeBytes = selectedAsset.optLong("size").takeIf { it > 0 },
            apkArchitecture = architecture,
            apkFileName = assetName,
            assetDigest = selectedAsset.optString("digest"),
            checksumsUrl = checksumsUrl,
        )
    }

    private fun resolveSha256(candidate: ReleaseCandidate): String {
        val apiDigest = candidate.assetDigest.removePrefix("sha256:")
            .takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        val checksumsDigest = candidate.checksumsUrl?.let { url ->
            runCatching { fetchChecksums(url, candidate.apkFileName) }.getOrNull()
        }
        if (apiDigest != null && checksumsDigest != null && !apiDigest.equals(checksumsDigest, ignoreCase = true)) {
            throw IOException("GitHub Release 的 APK 校验值不一致")
        }
        return checksumsDigest ?: apiDigest ?: throw IOException("GitHub Release 缺少 APK 的 SHA-256 校验值")
    }

    private fun fetchChecksums(url: String, apkFileName: String): String? {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", "PicoManager-Android")
        try {
            if (connection.responseCode !in 200..299) throw IOException("无法读取 SHA256SUMS.txt")
            val output = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.append(buffer, 0, count)
                    if (output.length > 256 * 1024) throw IOException("SHA256SUMS.txt 过大")
                }
            }
            return output.lineSequence().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2 || !parts[0].matches(Regex("[0-9a-fA-F]{64}"))) return@mapNotNull null
                val name = parts[1].trim().removePrefix("*").removePrefix("./")
                parts[0].takeIf { name == apkFileName }
            }.firstOrNull()
        } finally {
            connection.disconnect()
        }
    }

    private data class ReleaseCandidate(
        val versionName: String,
        val version: AppVersion,
        val releasePage: String,
        val notes: String,
        val apkDownloadUrl: String,
        val apkSizeBytes: Long?,
        val apkArchitecture: String,
        val apkFileName: String,
        val assetDigest: String,
        val checksumsUrl: String?,
    )

    private data class AppVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val previewNumber: Int?,
    ) : Comparable<AppVersion> {
        override fun compareTo(other: AppVersion): Int {
            compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)
                .takeIf { it != 0 }
                ?.let { return it }
            return when {
                previewNumber == null && other.previewNumber == null -> 0
                previewNumber == null -> 1
                other.previewNumber == null -> -1
                else -> previewNumber.compareTo(other.previewNumber)
            }
        }

        override fun toString(): String = buildString {
            append("$major.$minor.$patch")
            previewNumber?.let { append("-preview$it") }
        }

        companion object {
            fun parse(value: String): AppVersion? {
                val match = AppUpdateChecker.versionPattern.matchEntire(value) ?: return null
                return AppVersion(
                    major = match.groupValues[1].toIntOrNull() ?: return null,
                    minor = match.groupValues[2].toIntOrNull() ?: return null,
                    patch = match.groupValues[3].toIntOrNull() ?: return null,
                    previewNumber = if (value.contains("-preview")) {
                        match.groupValues[5].toIntOrNull() ?: 1
                    } else {
                        null
                    },
                )
            }
        }
    }
}
