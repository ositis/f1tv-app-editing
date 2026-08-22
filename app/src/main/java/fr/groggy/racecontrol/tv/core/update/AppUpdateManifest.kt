package fr.groggy.racecontrol.tv.core.update

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AppUpdateManifest(
    @Json(name = "versionCode") val versionCode: Int,
    @Json(name = "versionName") val versionName: String,
    @Json(name = "downloadUrl") val downloadUrl: String? = null,
    @Json(name = "apkUrl") val apkUrl: String? = null,
) {
    fun resolvedDownloadUrl(fallbackUrl: String): String {
        return downloadUrl?.takeIf { it.isNotBlank() }
            ?: apkUrl?.takeIf { it.isNotBlank() }
            ?: fallbackUrl
    }
}
