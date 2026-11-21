package org.artcraft.camcraft.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun semVerParsesAndOrdersCorrectly() {
        val v001 = SemVer.parse("v0.0.1")
        val v002 = SemVer.parse("0.0.2")
        val v009 = SemVer.parse("0.0.9")
        val v0010 = SemVer.parse("0.0.10")
        val alpha = SemVer.parse("v0.1.0-alpha.1")
        val beta = SemVer.parse("v0.1.0-beta.1")
        val rc2 = SemVer.parse("0.1.0-rc.2")
        val rc10 = SemVer.parse("0.1.0-rc.10")
        val stable = SemVer.parse("0.1.0+build.15")

        assertEquals(SemVer.parse("0.0.1"), v001)
        assertTrue(v001 < v002)
        assertTrue(v009 < v0010)
        assertTrue(alpha < beta)
        assertTrue(rc2 < rc10)
        assertTrue(beta < stable)
        assertTrue(rc10 < stable)

        assertNull(SemVer.tryParse(""))
        assertNull(SemVer.tryParse("1.2"))
        assertNull(SemVer.tryParse("1.2.3-"))
        assertNull(SemVer.tryParse("1.2.x"))
    }

    @Test
    fun evaluateReleaseJsonSelectsCamCraftAndroidApkAndChecksums() {
        val json = """
            {
              "tag_name": "v0.2.0",
              "body": "## Release\nLow-latency improvements",
              "html_url": "https://github.com/madhasoeki/CamCraft/releases/tag/v0.2.0",
              "draft": false,
              "assets": [
                {
                  "name": "camcraft-desktop-fedora-x86_64.tar.gz",
                  "browser_download_url": "https://example.invalid/desktop.tar.gz",
                  "size": 10000
                },
                {
                  "name": "other-debug.apk",
                  "browser_download_url": "https://example.invalid/other.apk",
                  "size": 2000
                },
                {
                  "name": "camcraft-android-v0.2.0.apk",
                  "browser_download_url": "https://example.invalid/camcraft-android-v0.2.0.apk",
                  "size": 4567890
                },
                {
                  "name": "SHA256SUMS.txt",
                  "browser_download_url": "https://example.invalid/SHA256SUMS.txt",
                  "size": 312
                }
              ]
            }
        """.trimIndent()

        val res = AppUpdater.evaluateReleaseJson(json, currentVersion = "0.0.1")
        assertTrue(res is UpdateCheckResult.UpdateAvailable)
        val info = (res as UpdateCheckResult.UpdateAvailable).release
        assertEquals("v0.2.0", info.tagName)
        assertEquals("0.2.0", info.version)
        assertEquals("camcraft-android-v0.2.0.apk", info.assetName)
        assertEquals(4567890L, info.assetSize)
        assertEquals("https://example.invalid/SHA256SUMS.txt", info.checksumsUrl)
    }

    @Test
    fun evaluateReleaseJsonFallsBackToGenericApkAndSkipsDraftOrOlder() {
        val draftJson = """
            {
              "tag_name": "v9.9.9",
              "draft": true,
              "assets": []
            }
        """.trimIndent()
        assertTrue(AppUpdater.evaluateReleaseJson(draftJson, "0.0.1") is UpdateCheckResult.UpToDate)

        val sameJson = """
            {
              "tag_name": "v0.0.1",
              "draft": false,
              "assets": []
            }
        """.trimIndent()
        assertTrue(AppUpdater.evaluateReleaseJson(sameJson, "0.0.1") is UpdateCheckResult.UpToDate)

        val fallbackApkJson = """
            {
              "tag_name": "v0.0.2",
              "draft": false,
              "assets": [
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://example.invalid/app-release.apk",
                  "size": 12345
                }
              ]
            }
        """.trimIndent()
        val res = AppUpdater.evaluateReleaseJson(fallbackApkJson, "0.0.1") as UpdateCheckResult.UpdateAvailable
        assertEquals("app-release.apk", res.release.assetName)
        assertNull(res.release.checksumsUrl)
    }

    @Test
    fun parseSha256SumsParsesTargetAssetDigest() {
        val digestA = "a".repeat(64)
        val digestB = "F".repeat(64)
        val text = """
            # SHA-256 Checksums
            $digestA  camcraft-desktop-linux-x86_64.tar.gz
            $digestB *./camcraft-android-v0.0.2.apk
        """.trimIndent()

        assertEquals(
            "f".repeat(64),
            AppUpdater.parseSha256Sums(text, "camcraft-android-v0.0.2.apk"),
        )
        assertNull(AppUpdater.parseSha256Sums(text, "missing.apk"))
    }
}
