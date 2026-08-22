package fr.groggy.racecontrol.tv.f1tv

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Editorial page payloads (Shows, Documentaries, etc.). */
@JsonClass(generateAdapter = true)
data class F1TvPageResponse(
    val resultObj: F1TvPageResult
)

@JsonClass(generateAdapter = true)
data class F1TvPageResult(
    val containers: List<F1TvPageContainer>?
)

@JsonClass(generateAdapter = true)
data class F1TvPageContainer(
    val id: String?,
    val metadata: F1TvPageItemMetadata?,
    val retrieveItems: F1TvPageRetrieveItems?
)

@JsonClass(generateAdapter = true)
data class F1TvPageRetrieveItems(
    val resultObj: F1TvPageResult?
)

@JsonClass(generateAdapter = true)
data class F1TvPageItemMetadata(
    val title: String?,
    val label: String?,
    val pictureUrl: String?,
    val contentId: String?,
    val contentSubtype: String?,
    val contentType: String?,
    val uiSeries: String? = null,
    val emfAttributes: F1TvPageEmfAttributes? = null
)

@JsonClass(generateAdapter = true)
data class F1TvPageEmfAttributes(
    @param:Json(name = "Series") val series: String? = null
)

enum class F1TvEditorialPage(val pageId: Int) {
    SHOWS(410),
    DOCUMENTARIES(413)
}
