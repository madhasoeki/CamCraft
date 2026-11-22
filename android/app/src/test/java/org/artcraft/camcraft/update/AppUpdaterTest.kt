package org.artcraft.camcraft.update

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun resolveRedirectUrlResolvesRelativeAndAbsoluteLocations() {
        val base = "https://github.com/madhasoeki/CamCraft/releases/download/v0.2.0/app.apk"
        assertEquals(
            "https://github.com/cdn/v0.2.0/app.apk",
            AppUpdater.resolveRedirectUrl(base, "/cdn/v0.2.0/app.apk"),
        )
        assertEquals(
            "https://github.com/madhasoeki/CamCraft/releases/download/v0.2.0/mirror.apk",
            AppUpdater.resolveRedirectUrl(base, "mirror.apk"),
        )
        assertEquals(
            "https://objects.githubusercontent.com/assets/app.apk",
            AppUpdater.resolveRedirectUrl(base, "//objects.githubusercontent.com/assets/app.apk"),
        )
        assertEquals(
            "https://cdn.example.invalid/app.apk",
            AppUpdater.resolveRedirectUrl(base, "https://cdn.example.invalid/app.apk"),
        )
    }

    @Test
    fun updateCheckCoordinatorEscalatesActiveSilentCheckToManualFeedback() = runBlocking {
        val gate = CompletableDeferred<UpdateCheckResult>()
        val state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
        val coordinator = UpdateCheckCoordinator(
            stateFlow = state,
            scope = this,
            performCheck = { gate.await() },
        )

        // Silent startup check starts in background without showing Checking UI.
        coordinator.check(silent = true)
        assertEquals(UpdateUiState.Idle, state.value)

        // Manual check while silent check is active escalates immediately to Checking.
        coordinator.check(silent = false)
        assertEquals(UpdateUiState.Checking, state.value)

        // Once the in-flight check resolves UpToDate, manual feedback is surfaced.
        gate.complete(UpdateCheckResult.UpToDate("0.0.3", "0.0.3"))
        val resolved = state.first { it !is UpdateUiState.Checking }
        assertEquals(UpdateUiState.UpToDate, resolved)

        // Subsequent silent check failing stays silent; manual escalation surfaces Error.
        val errGate = CompletableDeferred<UpdateCheckResult>()
        val errState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
        val errCoordinator = UpdateCheckCoordinator(
            stateFlow = errState,
            scope = this,
            performCheck = { errGate.await() },
        )
        errCoordinator.check(silent = true)
        errCoordinator.check(silent = false)
        assertEquals(UpdateUiState.Checking, errState.value)
        errGate.completeExceptionally(IllegalStateException("network offline"))
        val errResolved = errState.first { it !is UpdateUiState.Checking }
        assertTrue(errResolved is UpdateUiState.Error)
        assertEquals("network offline", (errResolved as UpdateUiState.Error).message)
    }

    @Test
    fun updateCheckCoordinatorBackToBackManualChecksAroundCompletionNeverStuckOnChecking() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            repeat(200) {
                val state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
                val firstEntered = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<UpdateCheckResult>()
                val calls = AtomicInteger(0)

                val coordinator = UpdateCheckCoordinator(
                    stateFlow = state,
                    scope = testScope,
                    performCheck = {
                        if (calls.incrementAndGet() == 1) {
                            firstEntered.complete(Unit)
                            gate.await()
                        } else {
                            UpdateCheckResult.UpToDate("0.0.3", "0.0.3")
                        }
                    },
                )

                coordinator.check(silent = true)
                withTimeout(2_000) { firstEntered.await() }

                val barrier = CyclicBarrier(3)
                val manualA = Thread {
                    barrier.await(2, TimeUnit.SECONDS)
                    coordinator.check(silent = false)
                }
                val manualB = Thread {
                    barrier.await(2, TimeUnit.SECONDS)
                    coordinator.check(silent = false)
                }
                manualA.start()
                manualB.start()
                barrier.await(2, TimeUnit.SECONDS)
                gate.complete(UpdateCheckResult.UpToDate("0.0.3", "0.0.3"))

                manualA.join(2_000)
                manualB.join(2_000)
                assertFalse(manualA.isAlive)
                assertFalse(manualB.isAlive)

                withTimeout(2_000) {
                    while (coordinator.isCheckInFlight) {
                        delay(1)
                    }
                }
                assertEquals(UpdateUiState.UpToDate, state.value)
            }
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun updateCheckCoordinatorCancellationResetsCheckJobAndEscalationCleanly() = runBlocking {
        val state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
        val calls = AtomicInteger(0)
        val firstStarted = CompletableDeferred<Unit>()
        val cancelTrigger = CompletableDeferred<Unit>()
        val secondCompleted = CompletableDeferred<Unit>()

        val coordinator = UpdateCheckCoordinator(
            stateFlow = state,
            scope = this,
            performCheck = {
                when (calls.incrementAndGet()) {
                    1 -> {
                        firstStarted.complete(Unit)
                        cancelTrigger.await()
                        currentCoroutineContext().cancel()
                        awaitCancellation()
                    }
                    2 -> {
                        UpdateCheckResult.UpToDate("0.0.3", "0.0.3").also {
                            secondCompleted.complete(Unit)
                        }
                    }
                    else -> UpdateCheckResult.UpToDate("0.0.3", "0.0.3")
                }
            },
        )

        // Start a manual check (escalatedToManual = true, checkJob != null) and cancel it mid-flight.
        coordinator.check(silent = false)
        withTimeout(2_000) { firstStarted.await() }
        assertEquals(UpdateUiState.Checking, state.value)
        assertTrue(coordinator.isCheckInFlight)

        cancelTrigger.complete(Unit)
        withTimeout(2_000) {
            while (coordinator.isCheckInFlight) {
                delay(5)
            }
        }
        assertFalse(coordinator.isCheckInFlight)

        // Subsequent silent check must start (checkJob cleared) and stay silent (escalatedToManual cleared).
        state.value = UpdateUiState.Idle
        coordinator.check(silent = true)
        withTimeout(2_000) {
            secondCompleted.await()
            while (coordinator.isCheckInFlight) {
                delay(5)
            }
        }
        assertEquals(2, calls.get())
        assertEquals(UpdateUiState.Idle, state.value)

        // Subsequent manual check must surface UpToDate normally.
        coordinator.check(silent = false)
        val resolved = withTimeout(2_000) { state.first { it is UpdateUiState.UpToDate } }
        assertEquals(3, calls.get())
        assertEquals(UpdateUiState.UpToDate, resolved)
    }
}
