package com.cold04.inkreadermgr

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppUpdateCheckerTest {
    private val sha256 = "a".repeat(64)

    @Test
    fun missingOrUnknownPreferenceDefaultsToGitee() {
        assertEquals(AppUpdateSource.GITEE, AppUpdateSource.fromPreference(null))
        assertEquals(AppUpdateSource.GITEE, AppUpdateSource.fromPreference("unknown"))
        assertEquals(AppUpdateSource.GITHUB, AppUpdateSource.fromPreference("github"))
    }

    @Test
    fun giteeAcceptsMissingGithubFieldsAndUsesChecksumAsset() {
        val source = AppUpdateSource.GITEE
        val release = release(source)
        val apk = release.getJSONArray("assets").getJSONObject(0)
        apk.remove("digest")
        apk.remove("size")
        var checksumFetched = false
        val result = AppUpdateChecker.checkReleases(
            "0.1.0", false, source, JSONArray().put(release), listOf("arm64-v8a"),
        ) { url, fileName ->
            assertEquals("${source.repositoryUrl}/releases/download/Android-v0.2.0/SHA256SUMS.txt", url)
            assertEquals("app-arm64-v8a-release.apk", fileName)
            checksumFetched = true
            sha256
        }

        assertTrue(checksumFetched)
        assertTrue(result.updateAvailable)
        val update = result.latestRelease!!
        assertEquals("0.2.0", update.versionName)
        assertEquals("${source.repositoryUrl}/releases/tag/Android-v0.2.0", update.releasePage)
        assertEquals(apk.getString("browser_download_url"), update.apkDownloadUrl)
        assertNull(update.apkSizeBytes)
        assertEquals("arm64-v8a", update.apkArchitecture)
        assertEquals(sha256, update.apkSha256)
    }

    @Test
    fun githubStillUsesApiDigestWhenChecksumCannotBeFetched() {
        val source = AppUpdateSource.GITHUB
        val result = check(source, release(source))

        assertTrue(result.updateAvailable)
        assertEquals(sha256, result.latestRelease!!.apkSha256)
        assertEquals(123L, result.latestRelease!!.apkSizeBytes)
    }

    @Test
    fun selectsLatestAndroidReleaseAndRespectsPreviewsAndDrafts() {
        for (source in AppUpdateSource.entries) {
            val releases = arrayOf(
                release(source, "Android-v0.3.0-preview2"),
                release(source, "iOS-v9.0.0"),
                release(source, "Android-v0.4.0").put("draft", true),
                release(source, "Android-v0.2.0"),
                release(source, "Android-v0.1.1"),
                release(source, "Android-v0.5.0").put("prerelease", true),
            )
            assertEquals("0.2.0", check(source, *releases).latestRelease!!.versionName)
            assertEquals("0.5.0", check(source, *releases, includePreviews = true).latestRelease!!.versionName)
            assertEquals(
                "0.3.0-preview2",
                check(source, releases[0], includePreviews = true).latestRelease!!.versionName,
            )
        }
    }

    @Test
    fun fallsBackToUniversalApkForOtherArchitectures() {
        val source = AppUpdateSource.GITEE
        val release = release(source)
        release.getJSONArray("assets").put(asset(source, "Android-v0.2.0", "app-universal-release.apk"))
        val result = check(source, release, abis = listOf("x86_64"))

        assertEquals("universal", result.latestRelease!!.apkArchitecture)
        assertTrue(result.latestRelease!!.apkDownloadUrl.endsWith("/app-universal-release.apk"))
    }

    @Test
    fun rejectsApkFromAnotherSource() {
        for (source in AppUpdateSource.entries) {
            val release = release(source)
            release.getJSONArray("assets").getJSONObject(0)
                .put("browser_download_url", "https://example.com/app.apk")
            assertFalse(check(source, release).updateAvailable)
        }
    }

    @Test
    fun missingChecksumFailsInsteadOfOfferingUnverifiedApk() {
        val source = AppUpdateSource.GITEE
        val release = release(source)
        release.getJSONArray("assets").getJSONObject(0).remove("digest")

        assertThrows(IOException::class.java) { check(source, release) }
    }

    @Test
    fun conflictingChecksumsFail() {
        val source = AppUpdateSource.GITHUB
        assertThrows(IOException::class.java) {
            AppUpdateChecker.checkReleases(
                "0.1.0", false, source, JSONArray().put(release(source)), listOf("arm64-v8a"),
            ) { _, _ -> "b".repeat(64) }
        }
    }

    @Test
    fun installedVersionDoesNotFetchChecksumOrOfferUpdate() {
        val source = AppUpdateSource.GITEE
        val result = AppUpdateChecker.checkReleases(
            "0.2.0", false, source, JSONArray().put(release(source)), listOf("arm64-v8a"),
        ) { _, _ -> error("No checksum request expected") }

        assertFalse(result.updateAvailable)
        assertNull(result.latestRelease)
    }

    private fun check(
        source: AppUpdateSource,
        vararg releases: JSONObject,
        includePreviews: Boolean = false,
        abis: List<String> = listOf("arm64-v8a"),
    ): AppUpdateCheck = AppUpdateChecker.checkReleases(
        "0.1.0", includePreviews, source, JSONArray(releases.toList()), abis,
    ) { _, _ -> null }

    private fun release(source: AppUpdateSource, tag: String = "Android-v0.2.0"): JSONObject =
        JSONObject().apply {
            put("tag_name", tag)
            put("prerelease", false)
            put("body", "Release notes")
            put("assets", JSONArray()
                .put(asset(source, tag, "app-arm64-v8a-release.apk"))
                .put(asset(source, tag, "SHA256SUMS.txt")))
            if (source == AppUpdateSource.GITHUB) {
                put("draft", false)
                put("html_url", "${source.repositoryUrl}/releases/tag/$tag")
            }
        }

    private fun asset(source: AppUpdateSource, tag: String, name: String): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("browser_download_url", "${source.repositoryUrl}/releases/download/$tag/$name")
            put("size", 123L)
            put("digest", "sha256:$sha256")
        }
}
