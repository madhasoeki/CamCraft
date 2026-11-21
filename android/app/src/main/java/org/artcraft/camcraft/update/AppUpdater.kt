package org.artcraft.camcraft.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.artcraft.camcraft.BuildConfig

@Serializable
data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val size: Long = 0L,
)

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name")
    val tagName: String,
    val body: String? = null,
    @SerialName("html_url")
    val htmlUrl: String = "",
    val draft: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList(),
)

data class ReleaseUpdateInfo(
    val tagName: String,
    val version: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val assetName: String,
    val downloadUrl: String,
    val assetSize: Long,
    val checksumsUrl: String?,
)

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateCheckResult
    data class UpdateAvailable(val release: ReleaseUpdateInfo) : UpdateCheckResult
}

sealed interface InstallLaunchResult {
    data object InstallerLaunched : InstallLaunchResult
    data object NeedsUnknownSourcesPermission : InstallLaunchResult
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val release: ReleaseUpdateInfo) : UpdateUiState
    data class Downloading(
        val version: String,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateUiState

    data class ReadyToInstall(val release: ReleaseUpdateInfo, val apkFile: File) : UpdateUiState
    data class NeedsInstallPermission(val release: ReleaseUpdateInfo, val apkFile: File) : UpdateUiState
    data class Error(val message: String, val retryRelease: ReleaseUpdateInfo? = null) : UpdateUiState
}

object AppUpdater {
    private const val CHECKSUM_FILE_NAME = "SHA256SUMS.txt"
    private const val MAX_APK_BYTES = 256L * 1024L * 1024L
    private const val BUFFER_BYTES = 32 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun checkForUpdate(
        repo: String = BuildConfig.GITHUB_REPO,
        currentVersion: String = BuildConfig.VERSION_NAME,
        apiBaseUrl: String = "https://api.github.com",
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val cleanBase = apiBaseUrl.trimEnd('/')
        val url = "$cleanBase/repos/$repo/releases/latest"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CamCraft-Android/$currentVersion")
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return@withContext UpdateCheckResult.UpToDate(
                    currentVersion = currentVersion,
                    latestVersion = currentVersion,
                )
            }
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IOException("GitHub API HTTP $code: ${body.take(160)}")
            }
            evaluateReleaseJson(body, currentVersion)
        } finally {
            conn.disconnect()
        }
    }

    internal fun evaluateReleaseJson(
        releaseJson: String,
        currentVersion: String,
    ): UpdateCheckResult {
        val currentSv = SemVer.parse(currentVersion)
        val release = json.decodeFromString(GitHubReleaseDto.serializer(), releaseJson)
        if (release.draft) {
            return UpdateCheckResult.UpToDate(
                currentVersion = currentVersion,
                latestVersion = currentVersion,
            )
        }

        val latestSv = SemVer.parse(release.tagName)
        if (latestSv <= currentSv) {
            return UpdateCheckResult.UpToDate(
                currentVersion = currentVersion,
                latestVersion = release.tagName,
            )
        }

        val apkAsset = selectAndroidApkAsset(release.assets)
            ?: throw IOException("No Android APK asset found in release ${release.tagName}")

        val checksumsUrl = release.assets
            .firstOrNull { it.name == CHECKSUM_FILE_NAME }
            ?.browserDownloadUrl

        return UpdateCheckResult.UpdateAvailable(
            ReleaseUpdateInfo(
                tagName = release.tagName,
                version = latestSv.toString(),
                releaseNotes = release.body.orEmpty(),
                htmlUrl = release.htmlUrl,
                assetName = apkAsset.name,
                downloadUrl = apkAsset.browserDownloadUrl,
                assetSize = apkAsset.size,
                checksumsUrl = checksumsUrl,
            ),
        )
    }

    internal fun selectAndroidApkAsset(assets: List<GitHubAssetDto>): GitHubAssetDto? {
        return assets.firstOrNull {
            it.name.startsWith("camcraft-android-", ignoreCase = true) &&
                it.name.endsWith(".apk", ignoreCase = true)
        } ?: assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true)
        }
    }

    internal fun parseSha256Sums(content: String, assetName: String): String? {
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) continue
            val hash = parts[0]
            val cleanName = parts[1].trimStart('*').removePrefix("./")
            if (cleanName == assetName) {
                require(hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    "Malformed SHA-256 digest for $assetName"
                }
                return hash.lowercase()
            }
        }
        return null
    }

    suspend fun downloadAndVerifyApk(
        context: Context,
        info: ReleaseUpdateInfo,
        onProgress: (Float, Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (info.assetSize > MAX_APK_BYTES) {
            throw IOException("APK size ${info.assetSize} exceeds limit")
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updatesDir.listFiles()?.forEach { old ->
            if (old.isFile) old.delete()
        }

        val safeName = info.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val targetApk = File(updatesDir, safeName)
        val tempApk = File(updatesDir, "$safeName.part")

        val digest = MessageDigest.getInstance("SHA-256")
        val conn = openUrlFollowingRedirects(info.downloadUrl)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("APK download failed with HTTP $code")
            }
            val headerLen = conn.contentLengthLong.coerceAtLeast(0L)
            val totalBytes = if (info.assetSize > 0L) info.assetSize else headerLen
            if (totalBytes > MAX_APK_BYTES) {
                throw IOException("APK payload exceeds safety limit")
            }

            var downloaded = 0L
            val buffer = ByteArray(BUFFER_BYTES)
            conn.inputStream.use { input ->
                FileOutputStream(tempApk).use { out ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        downloaded += read
                        if (downloaded > MAX_APK_BYTES) {
                            throw IOException("APK download exceeded safety limit")
                        }
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        val effectiveTotal = maxOf(totalBytes, downloaded).coerceAtLeast(1L)
                        val frac = (downloaded.toFloat() / effectiveTotal.toFloat()).coerceIn(0f, 1f)
                        onProgress(frac, downloaded, totalBytes)
                    }
                    out.flush()
                }
            }

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.checksumsUrl != null) {
                val checksumText = fetchText(info.checksumsUrl)
                val expectedHash = parseSha256Sums(checksumText, info.assetName)
                    ?: throw IOException("Entry ${info.assetName} missing in $CHECKSUM_FILE_NAME")
                if (actualHash != expectedHash) {
                    tempApk.delete()
                    throw IOException("SHA-256 mismatch: expected $expectedHash, got $actualHash")
                }
            }

            if (targetApk.exists()) targetApk.delete()
            if (!tempApk.renameTo(targetApk)) {
                tempApk.delete()
                throw IOException("Failed to finalize downloaded APK")
            }
            targetApk
        } catch (t: Throwable) {
            tempApk.delete()
            throw t
        } finally {
            conn.disconnect()
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File): InstallLaunchResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return InstallLaunchResult.NeedsUnknownSourcesPermission
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return InstallLaunchResult.InstallerLaunched
    }

    private fun fetchText(urlString: String): String {
        val conn = openUrlFollowingRedirects(urlString)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code fetching checksums")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openUrlFollowingRedirects(urlString: String): HttpURLConnection {
        var current = urlString
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "CamCraft-Android/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == 307 ||
                code == 308
            ) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) throw IOException("Redirect without Location header")
                current = next
            } else {
                return conn
            }
        }
        throw IOException("Too many HTTP redirects")
    }
}
