package fr.groggy.racecontrol.tv.core.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.utils.http.execute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed class AppUpdateCheckResult {
    data object UpToDate : AppUpdateCheckResult()
    data class UpdateAvailable(val manifest: AppUpdateManifest) : AppUpdateCheckResult()
    data class Error(val message: String) : AppUpdateCheckResult()
}

class InstallPermissionRequiredException : Exception()

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    moshi: Moshi,
) {
    private val manifestAdapter = moshi.adapter(AppUpdateManifest::class.java)

    private val manifestUrls: List<String> = BuildConfig.UPDATE_MANIFEST_URLS
        .split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val installedVersionCode: Int
        get() = BuildConfig.VERSION_CODE

    val installedVersionName: String
        get() = BuildConfig.VERSION_NAME

    fun canInstallPackages(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        activity.startActivity(intent)
    }

    suspend fun checkForUpdate(): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        var lastError: String? = null
        for (url in manifestUrls) {
            try {
                val manifest = fetchManifest(url)
                return@withContext if (manifest.versionCode > installedVersionCode) {
                    AppUpdateCheckResult.UpdateAvailable(manifest)
                } else {
                    AppUpdateCheckResult.UpToDate
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "Update manifest failed for $url", e)
            }
        }
        AppUpdateCheckResult.Error(lastError ?: "Could not reach update server")
    }

    suspend fun downloadUpdate(
        manifest: AppUpdateManifest,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val downloadUrl = manifest.resolvedDownloadUrl(BuildConfig.UPDATE_APK_FALLBACK_URL)
        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", BuildConfig.DEFAULT_USER_AGENT)
            .build()
        val response = request.execute(httpClient)
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Download failed (${response.code})")
        }

        val body = response.body ?: throw IOException("Empty download body")
        val totalBytes = body.contentLength().coerceAtLeast(0L)
        val updatesDir = context.getExternalFilesDir("updates") ?: context.cacheDir
        updatesDir.mkdirs()
        val targetFile = File(updatesDir, "UgisF1-${manifest.versionName}.apk")
        if (targetFile.exists()) targetFile.delete()

        body.byteStream().use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, totalBytes)
                }
                output.flush()
            }
        }
        response.close()
        targetFile
    }

    fun installDownloadedApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallPackages()) {
            throw InstallPermissionRequiredException()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            installWithPackageInstaller(apkFile)
        } else {
            installWithViewIntent(apkFile)
        }
    }

    private fun installWithPackageInstaller(apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        apkFile.inputStream().use { input ->
            session.openWrite("UgisF1", 0, apkFile.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val callbackIntent = Intent(context, AppUpdateInstallReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId,
            callbackIntent,
            pendingIntentFlags
        )
        session.commit(pendingIntent.intentSender)
        session.close()
    }

    private fun installWithViewIntent(apkFile: File) {
        val uri = fileProviderUri(apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun fileProviderUri(apkFile: File): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

    private fun fetchManifest(url: String): AppUpdateManifest {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BuildConfig.DEFAULT_USER_AGENT)
            .header("Cache-Control", "no-cache")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("Manifest request failed (${it.code})")
            val body = it.body?.string() ?: throw IOException("Empty manifest body")
            return manifestAdapter.fromJson(body) ?: throw IOException("Invalid manifest JSON")
        }
    }

    companion object {
        private const val TAG = "AppUpdateManager"
    }
}
