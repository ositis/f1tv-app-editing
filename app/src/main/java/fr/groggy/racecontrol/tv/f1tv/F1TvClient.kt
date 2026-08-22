package fr.groggy.racecontrol.tv.f1tv

import android.util.Log
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import fr.groggy.racecontrol.tv.core.InstantPeriod
import fr.groggy.racecontrol.tv.utils.http.execute
import fr.groggy.racecontrol.tv.utils.http.parseJsonBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.threeten.bp.Instant
import org.threeten.bp.OffsetDateTime
import org.threeten.bp.Year
import org.threeten.bp.ZoneId
import org.threeten.bp.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class F1TvClient @Inject constructor(
    private val httpClient: OkHttpClient,
    moshi: Moshi
) {

    companion object {
        const val MAIN_IMAGE_WIDTH = 640
        const val MAIN_IMAGE_HEIGHT = 360

        private val TAG = F1TvClient::class.simpleName
        private const val ROOT_URL = "https://f1tv.formula1.com"

        private const val GROUP_ID = 2 //TODO this might need to be migrated to the correct ONE
        private const val LIST_SEASON = "/2.0/R/%s/WEB_HLS/ALL/PAGE/SEARCH/VOD/F1_TV_Premium_Monthly/$GROUP_ID?filter_objectSubtype=Meeting&filter_season=%s&filter_orderByFom=Y&maxResults=100"
        private const val LIST_SESSIONS = "/2.0/R/%s/WEB_HLS/ALL/PAGE/SANDWICH/F1_TV_Premium_Monthly/$GROUP_ID?meetingId=%s&title=weekend-sessions"
        private const val LIST_FUTURE_SESSIONS = "/2.0/R/%s/WEB_HLS/ALL/PAGE/1350/F1_TV_Premium_Monthly/$GROUP_ID"
        private const val LIST_CHANNELS = "/3.0/R/%s/WEB_HLS/ALL/CONTENT/VIDEO/%s/F1_TV_Premium_Monthly/$GROUP_ID"
        private const val LIST_EDITORIAL_PAGE = "/2.0/R/%s/WEB_HLS/ALL/PAGE/%d/F1_TV_Premium_Monthly/$GROUP_ID"
        private const val PICTURE_URL = "$ROOT_URL/image-resizer/image/%s?w=$MAIN_IMAGE_WIDTH&h=$MAIN_IMAGE_HEIGHT&o=L&q=HI"
        private const val LARGE_PICTURE_URL = "$ROOT_URL/image-resizer/image/%s?w=1920&h=1080&o=L&q=HI"
    }

    private val seasonResponseJsonAdapter = moshi.adapter(F1TvSeasonResponse::class.java)
    private val sessionResponseJsonAdapter = moshi.adapter(F1TvSessionResponse::class.java)
    private val futureSessionResponseJsonAdapter = moshi.adapter(F1TvFutureSessionResponse::class.java)
    private val channelResponseJsonAdapter = moshi.adapter(F1TvChannelResponse::class.java)
    private val sessionArchiveJsonAdapter = moshi.adapter(SessionArchive::class.java)
    private val pageResponseJsonAdapter = moshi.adapter(F1TvPageResponse::class.java)
    private val archiveSortInstant = Instant.EPOCH

    suspend fun getSeason(archive: Archive): F1TvSeason {
        val response = get(LIST_SEASON.format(getCurrentLocale(), archive.year), seasonResponseJsonAdapter)
        Log.d(TAG, "Fetched season $archive")
        return F1TvSeason(
            year = Year.of(archive.year),
            title = archive.year.toString(),
            events = response.resultObj.containers.map {
                F1TvSeasonEvent(
                    id = it.id,
                    meetingKey = it.metadata.emfAttributes.meetingKey,
                    title = it.metadata.emfAttributes.title,
                    period = InstantPeriod(
                        start = parseOffsetDateSafely(it.metadata.emfAttributes.startDate),
                        end = parseOffsetDateSafely(it.metadata.emfAttributes.endDate)
                    )
                )
            },
            detailAction = response.resultObj.containers.firstOrNull()?.actions?.firstOrNull { it.targetType == "DETAILS_PAGE" }?.uri
        )
    }

    suspend fun getSessions(event: F1TvSeasonEvent, season: F1TvSeason): List<F1TvSession> {
        return when {
            season.year.value < 2018 -> {
                getSessionArchive(event, season)
            }
            event.period.start < Instant.now() -> {
                getF1TvSessions(event)
            }
            else -> {
                return listOf()
            }
        }
    }

    private suspend fun getSessionArchive(event: F1TvSeasonEvent, season: F1TvSeason): List<F1TvSession> {
        try {
            val result = get(season.detailAction!!, sessionArchiveJsonAdapter)
            return result.resultObj.containers.mapNotNull { sessionArchiveContainer ->
                sessionArchiveContainer.retrieveItems.resultObj.containers
            }.flatten().map {
                F1TvSession(
                    id = F1TvSessionId(it.id),
                    eventId = event.id,
                    pictureUrl = PICTURE_URL.format(it.metadata.pictureUrl),
                    contentId = it.metadata.contentId,
                    largePictureUrl = LARGE_PICTURE_URL.format(it.metadata.pictureUrl),
                    name = it.metadata.title,
                    contentSubtype = it.metadata.contentSubtype,
                    series = RacingSeries.classify(
                        uiSeries = null,
                        series = null,
                        title = it.metadata.title
                    ),
                    period = InstantPeriod(
                        start = archiveSortInstant,
                        end = archiveSortInstant
                    ),
                    available = true,
                    images = listOf(),
                    channels = listOf()
                )
            }
        } catch (_: Exception) {
            return listOf()
        }
    }

    private suspend fun getBroadcastF1TvSessions(event: F1TvSeasonEvent): List<F1TvSession> {
        try {
            val response = get(
                LIST_SESSIONS.format(getCurrentLocale(), event.meetingKey),
                sessionResponseJsonAdapter
            )
            Log.d(TAG, "Fetched broadcasted sessions for event ${event.id}")
            return response.resultObj.containers.map {
                F1TvSession(
                    id = F1TvSessionId(it.id),
                    eventId = event.id,
                    pictureUrl = PICTURE_URL.format(it.metadata.pictureUrl),
                    contentId = it.metadata.contentId,
                    largePictureUrl = LARGE_PICTURE_URL.format(it.metadata.pictureUrl),
                    name = it.metadata.title,
                    contentSubtype = it.metadata.contentSubtype,
                    series = RacingSeries.classify(
                        uiSeries = it.metadata.uiSeries,
                        series = it.metadata.emfAttributes.series,
                        title = it.metadata.title
                    ),
                    period = InstantPeriod(
                        start = parseOffsetDateSafely(it.metadata.emfAttributes.startDate),
                        end = parseOffsetDateSafely(it.metadata.emfAttributes.endDate)
                    ),
                    available = true,
                    images = listOf(),
                    channels = listOf()
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "getF1TvSessions failed with ${e.message}")
            return listOf()
        }
    }

    private suspend fun getF1TvSessions(event: F1TvSeasonEvent): List<F1TvSession> {
        val list = mutableListOf<F1TvSession>()
        if (event.period.start < Instant.now() && event.period.end > Instant.now()) {
            list.addAll(getFutureF1TvSessions(event))
        }
        list.addAll(getBroadcastF1TvSessions(event))
        return list
    }

    private suspend fun getFutureF1TvSessions(event: F1TvSeasonEvent): List<F1TvSession> {
        val dateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
        try {
            val response = get(
                LIST_FUTURE_SESSIONS.format(getCurrentLocale(), event.meetingKey),
                futureSessionResponseJsonAdapter
            )
            Log.d(TAG, "Fetched future sessions for event ${event.id}")
            val schedules = mutableListOf<F1TvFutureSessionEvent>()
            response.resultObj.containers
                .filter { it.layout == "schedule" }
                .forEach { it ->
                    it.retrieveItems.resultObj.containers
                        .filter { it.eventName.equals("ALL") }
                        .forEach { ev ->
                            ev.events!!
                                .filter {
                                    it.metadata.emfAttributes.sessionStartDate > Instant.now()
                                        .toEpochMilli()
                                }
                                .forEach { fev ->
                                    schedules.add(fev)
                                    Log.d(TAG, fev.toString())
                                }
                        }
                }
            return schedules.map {
                F1TvSession(
                    id = F1TvSessionId(it.id),
                    eventId = event.id,
                    pictureUrl = PICTURE_URL.format(it.metadata.pictureUrl),
                    contentId = it.metadata.contentId,
                    largePictureUrl = LARGE_PICTURE_URL.format(it.metadata.pictureUrl),
                    name = it.metadata.title,
                    contentSubtype = dateTimeFormatter.format(Instant.ofEpochMilli(it.metadata.emfAttributes.sessionStartDate)),
                    series = RacingSeries.classify(
                        uiSeries = it.metadata.uiSeries,
                        series = it.metadata.emfAttributes.series,
                        title = it.metadata.title
                    ),
                    period = InstantPeriod(
                        start = Instant.ofEpochMilli(it.metadata.emfAttributes.sessionStartDate),
                        end = Instant.ofEpochMilli(it.metadata.emfAttributes.sessionEndDate)
                    ),
                    available = true,
                    images = listOf(),
                    channels = listOf()
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "getFutureF1TvSessions failed with ${e.message}")
            return listOf()
        }
    }

    /*
     * Addresses issues with F1 dates
     * sometimes the start date is missing
     */
    private fun parseOffsetDateSafely(date: String?): Instant {
        return try {
            OffsetDateTime.parse(date).toInstant()
        } catch (e: Exception) {
//            Log.d(TAG, "Unable to parse date ${e.message}")
            archiveSortInstant //Less than ideal but at least we can see something
        }
    }

    suspend fun getEditorialPageVideos(page: F1TvEditorialPage): List<F1TvSession> {
        return try {
            val response = get(
                LIST_EDITORIAL_PAGE.format(getCurrentLocale(), page.pageId),
                pageResponseJsonAdapter
            )
            flattenPageVideos(response.resultObj.containers)
                .distinctBy { it.contentId }
                .sortedByDescending { it.period.start }
                .also { Log.d(TAG, "Fetched editorial page ${page.name}: ${it.size} videos") }
        } catch (e: Exception) {
            Log.w(TAG, "getEditorialPageVideos(${page.name}) failed: ${e.message}")
            emptyList()
        }
    }

    private fun flattenPageVideos(containers: List<F1TvPageContainer>?): List<F1TvSession> {
        if (containers.isNullOrEmpty()) return emptyList()
        val result = mutableListOf<F1TvSession>()
        for (container in containers) {
            container.toPageVideoSession()?.let { result += it }
            result += flattenPageVideos(container.retrieveItems?.resultObj?.containers)
        }
        return result
    }

    private fun F1TvPageContainer.toPageVideoSession(): F1TvSession? {
        val meta = metadata ?: return null
        val contentId = meta.contentId?.trim().orEmpty()
        if (contentId.isBlank()) return null
        val title = meta.title?.trim().orEmpty().ifBlank { meta.label?.trim().orEmpty() }
        if (title.isBlank()) return null
        val contentType = meta.contentType?.uppercase().orEmpty()
        if (contentType.isNotBlank() && contentType !in setOf("VIDEO", "BUNDLE", "EPISODE")) {
            return null
        }
        val picture = meta.pictureUrl?.trim().orEmpty()
        return F1TvSession(
            id = F1TvSessionId(id?.takeIf { it.isNotBlank() } ?: contentId),
            eventId = "editorial",
            pictureUrl = if (picture.isNotBlank()) PICTURE_URL.format(picture) else "",
            contentId = contentId,
            largePictureUrl = if (picture.isNotBlank()) LARGE_PICTURE_URL.format(picture) else "",
            name = title,
            contentSubtype = meta.contentSubtype?.trim().orEmpty().ifBlank { "SHOW" },
            series = RacingSeries.classify(
                uiSeries = meta.uiSeries,
                series = meta.emfAttributes?.series,
                title = title
            ),
            period = InstantPeriod(start = archiveSortInstant, end = archiveSortInstant),
            available = true,
            images = listOf(),
            channels = listOf()
        )
    }

    suspend fun getChannels(contentId: String): List<F1TvChannel> {
        try {
            val response = get(LIST_CHANNELS.format(getCurrentLocale(), contentId), channelResponseJsonAdapter)
            return response.resultObj.containers.firstOrNull()?.metadata?.additionalStreams
                ?.sortedBy { it.default }
                ?.map {

                val channelAndContentId = it.playbackUrl.findChannelAndContentId()
                if (it.type == "obc") {
                    F1TvOnboardChannel(
                        channelAndContentId.second,
                        channelAndContentId.first,
                        name = "${it.driverFirstName} ${it.driverLastName} ${it.racingNumber}",
                        background = it.hex,
                        subTitle = it.teamName,
                        driver = F1TvDriverId("") //TODO - do we have to load the driver ?
                    )
                } else {
                    F1TvBasicChannel(
                        channelAndContentId.second.ifEmpty { null },
                        channelAndContentId.first,
                        type = F1TvBasicChannelType.from(it.type, it.title, it.identifier)
                    )
                }
            } ?: listOf()
        } catch (e: Exception) {
            e.printStackTrace()
            return listOf()
        }
    }

    private fun String.findChannelAndContentId(): Pair<String, String> {
        val url = "$ROOT_URL$this".toHttpUrlOrNull()
        val contentId = url?.queryParameter("contentId")
        val channelId = url?.queryParameter("channelId")

        return contentId.orEmpty() to channelId.orEmpty()
    }

    private suspend fun <T> get(apiUrl: String, jsonAdapter: JsonAdapter<T>): T {
        val request = Request.Builder()
            .url("$ROOT_URL$apiUrl")
            .get()
            .build()
        return request.execute(httpClient).parseJsonBody(jsonAdapter)
    }

    /**
     * This is the locale supported by F1TvAPI
     * currently these are the only locales that it supports
     */
    private fun getCurrentLocale(): String {
        return when (val isO3Language = Locale.getDefault().isO3Language) {
            "deu", "fra", "nld", "spa", "por" -> isO3Language.uppercase()
            else -> "ENG"
        }
    }
}
