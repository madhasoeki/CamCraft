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
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val checkCoordinator = UpdateCheckCoordinator(
        stateFlow = _state,
        scope = scope,
        performCheck = { AppUpdater.checkForUpdate() },
    )

    fun checkOnStartup() {
        if (!startupChecked.compareAndSet(false, true)) return
        checkCoordinator.check(silent = true)
    }

    fun checkManually() {
        val cur = _state.value
        if (cur is UpdateUiState.Downloading ||
            cur is UpdateUiState.ReadyToInstall ||
            cur is UpdateUiState.NeedsInstallPermission
        ) {
            return
        }
        checkCoordinator.check(silent = false)
    }

    fun downloadAndInstall(context: Context) {
        val appCtx = context.applicationContext ?: context
        val release = when (val cur = _state.value) {
            is UpdateUiState.Available -> cur.release
            is UpdateUiState.Error -> cur.retryRelease ?: return
            is UpdateUiState.ReadyToInstall -> {
                launchInstallerAndUpdateState(appCtx, cur.release, cur.apkFile)
                return
            }
            is UpdateUiState.NeedsInstallPermission -> {
                launchInstallerAndUpdateState(appCtx, cur.release, cur.apkFile)
                return
            }
            else -> return
        }

        if (downloadJob?.isActive == true) return
        _state.value = UpdateUiState.Downloading(
            version = release.version,
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = release.assetSize,
        )

        downloadJob = scope.launch {
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
                launchInstallerAndUpdateState(appCtx, release, apkFile)
            }.onFailure { err ->
                _state.value = UpdateUiState.Error(
                    message = err.message ?: "Update download failed",
                    retryRelease = release,
                )
            }
        }
    }

    fun installDownloadedApk(context: Context) {
        val appCtx = context.applicationContext ?: context
        val (release, apkFile) = when (val cur = _state.value) {
            is UpdateUiState.ReadyToInstall -> cur.release to cur.apkFile
            is UpdateUiState.NeedsInstallPermission -> cur.release to cur.apkFile
            else -> return
        }
        launchInstallerAndUpdateState(appCtx, release, apkFile)
    }

    fun onResumeCheckPendingInstall(context: Context) {
        val appCtx = context.applicationContext ?: context
        val pending = _state.value as? UpdateUiState.NeedsInstallPermission ?: return
        if (appCtx.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateUiState.ReadyToInstall(
                release = pending.release,
                apkFile = pending.apkFile,
            )
            launchInstallerAndUpdateState(appCtx, pending.release, pending.apkFile)
        }
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }

    private fun launchInstallerAndUpdateState(
        appContext: Context,
        release: ReleaseUpdateInfo,
        apkFile: java.io.File,
    ) {
        val appCtx = appContext.applicationContext ?: appContext
        runCatching { AppUpdater.launchApkInstaller(appCtx, apkFile) }
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

/**
 * Coordinates silent startup vs explicit manual update checks so a manual check that
 * overlaps an active silent check escalates it to `Checking` and surfaces final UI feedback.
 */
internal class UpdateCheckCoordinator(
    private val stateFlow: MutableStateFlow<UpdateUiState>,
    private val scope: CoroutineScope,
    private val performCheck: suspend () -> UpdateCheckResult,
) {
    private val lock = Any()
    private var checkJob: Job? = null
    private var escalatedToManual = false

    fun check(silent: Boolean) {
        synchronized(lock) {
            if (checkJob?.isActive == true) {
                if (!silent) {
                    escalatedToManual = true
                    val cur = stateFlow.value
                    if (cur !is UpdateUiState.Downloading &&
                        cur !is UpdateUiState.ReadyToInstall &&
                        cur !is UpdateUiState.NeedsInstallPermission
                    ) {
                        stateFlow.value = UpdateUiState.Checking
                    }
                }
                return
            }

            escalatedToManual = !silent
            if (!silent) {
                stateFlow.value = UpdateUiState.Checking
            }

            checkJob = scope.launch {
                val outcome = runCatching { performCheck() }
                val treatAsManual = synchronized(lock) {
                    val manual = escalatedToManual
                    escalatedToManual = false
                    manual
                }
                outcome
                    .onSuccess { result ->
                        when (result) {
                            is UpdateCheckResult.UpdateAvailable -> {
                                stateFlow.value = UpdateUiState.Available(result.release)
                            }
                            is UpdateCheckResult.UpToDate -> {
                                if (treatAsManual) {
                                    stateFlow.value = UpdateUiState.UpToDate
                                }
                            }
                        }
                    }
                    .onFailure { err ->
                        if (treatAsManual) {
                            stateFlow.value = UpdateUiState.Error(
                                message = err.message ?: "Unable to check for updates",
                            )
                        }
                    }
            }
        }
    }
}
