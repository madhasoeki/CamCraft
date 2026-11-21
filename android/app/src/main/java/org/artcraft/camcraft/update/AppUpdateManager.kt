package org.artcraft.camcraft.update

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-scoped update coordinator keeping network & SHA-256 verification off the Main thread.
 */
object AppUpdateManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupChecked = AtomicBoolean(false)
    private var activeJob: Job? = null

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    fun checkOnStartup() {
        if (!startupChecked.compareAndSet(false, true)) return
        checkInternal(silent = true)
    }

    fun checkManually() {
        checkInternal(silent = false)
    }

    private fun checkInternal(silent: Boolean) {
        if (activeJob?.isActive == true) return
        if (!silent) {
            _state.value = UpdateUiState.Checking
        }
        activeJob = scope.launch {
            runCatching { AppUpdater.checkForUpdate() }
                .onSuccess { result ->
                    when (result) {
                        is UpdateCheckResult.UpdateAvailable -> {
                            _state.value = UpdateUiState.Available(result.release)
                        }
                        is UpdateCheckResult.UpToDate -> {
                            if (!silent) {
                                _state.value = UpdateUiState.UpToDate
                            }
                        }
                    }
                }
                .onFailure { err ->
                    if (!silent) {
                        _state.value = UpdateUiState.Error(
                            message = err.message ?: "Unable to check for updates",
                        )
                    }
                }
        }
    }

    fun downloadAndInstall(context: Context) {
        val release = when (val cur = _state.value) {
            is UpdateUiState.Available -> cur.release
            is UpdateUiState.Error -> cur.retryRelease ?: return
            is UpdateUiState.ReadyToInstall -> {
                launchInstallerAndUpdateState(context, cur.release, cur.apkFile)
                return
            }
            is UpdateUiState.NeedsInstallPermission -> {
                launchInstallerAndUpdateState(context, cur.release, cur.apkFile)
                return
            }
            else -> return
        }

        if (activeJob?.isActive == true) return
        val appCtx = context.applicationContext
        _state.value = UpdateUiState.Downloading(
            version = release.version,
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = release.assetSize,
        )

        activeJob = scope.launch {
            var lastEmitMs = 0L
            var lastPct = -1
            runCatching {
                AppUpdater.downloadAndVerifyApk(appCtx, release) { frac, dl, total ->
                    val pct = (frac * 100f).toInt()
                    val now = SystemClock.elapsedRealtime()
                    if (pct > lastPct || now - lastEmitMs >= 100L) {
                        lastPct = pct
                        lastEmitMs = now
                        _state.value = UpdateUiState.Downloading(
                            version = release.version,
                            progress = frac,
                            downloadedBytes = dl,
                            totalBytes = total,
                        )
                    }
                }
            }.onSuccess { apkFile ->
                _state.value = UpdateUiState.ReadyToInstall(release = release, apkFile = apkFile)
                launchInstallerAndUpdateState(context, release, apkFile)
            }.onFailure { err ->
                _state.value = UpdateUiState.Error(
                    message = err.message ?: "Update download failed",
                    retryRelease = release,
                )
            }
        }
    }

    fun installDownloadedApk(context: Context) {
        val (release, apkFile) = when (val cur = _state.value) {
            is UpdateUiState.ReadyToInstall -> cur.release to cur.apkFile
            is UpdateUiState.NeedsInstallPermission -> cur.release to cur.apkFile
            else -> return
        }
        launchInstallerAndUpdateState(context, release, apkFile)
    }

    fun onResumeCheckPendingInstall(context: Context) {
        val pending = _state.value as? UpdateUiState.NeedsInstallPermission ?: return
        if (context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateUiState.ReadyToInstall(
                release = pending.release,
                apkFile = pending.apkFile,
            )
            launchInstallerAndUpdateState(context, pending.release, pending.apkFile)
        }
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }

    private fun launchInstallerAndUpdateState(
        context: Context,
        release: ReleaseUpdateInfo,
        apkFile: java.io.File,
    ) {
        runCatching { AppUpdater.launchApkInstaller(context, apkFile) }
            .onSuccess { result ->
                _state.value = when (result) {
                    InstallLaunchResult.InstallerLaunched -> UpdateUiState.ReadyToInstall(release, apkFile)
                    InstallLaunchResult.NeedsUnknownSourcesPermission -> {
                        UpdateUiState.NeedsInstallPermission(release, apkFile)
                    }
                }
            }
            .onFailure { err ->
                _state.value = UpdateUiState.Error(
                    message = err.message ?: "Failed to launch APK installer",
                    retryRelease = release,
                )
            }
    }
}
