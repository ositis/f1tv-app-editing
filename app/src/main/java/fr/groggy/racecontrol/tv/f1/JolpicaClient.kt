package fr.groggy.racecontrol.tv.f1

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import fr.groggy.racecontrol.tv.utils.http.execute
import fr.groggy.racecontrol.tv.utils.http.parseJsonBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Open F1 results / standings / schedule via Jolpica (Ergast-compatible).
 * Used for race calendar, championship standings, and results browser.
 */
@Singleton
class JolpicaClient @Inject constructor(
    private val httpClient: OkHttpClient,
    moshi: Moshi
) {
    companion object {
        private const val ROOT = "https://api.jolpi.ca/ergast/f1"
    }

    private val mrDataAdapter = moshi.adapter(JolpicaEnvelope::class.java)

    suspend fun currentSeasonSchedule(): List<JolpicaRace> {
        val envelope = get("$ROOT/current.json?limit=40")
        return envelope.mrData.raceTable?.races.orEmpty()
    }

    suspend fun driverStandings(season: String = "current"): List<JolpicaDriverStanding> {
        val envelope = get("$ROOT/$season/driverstandings/?limit=40")
        return envelope.mrData.standingsTable?.standingsLists
            ?.firstOrNull()
            ?.driverStandings
            .orEmpty()
    }

    suspend fun constructorStandings(season: String = "current"): List<JolpicaConstructorStanding> {
        val envelope = get("$ROOT/$season/constructorstandings/?limit=30")
        return envelope.mrData.standingsTable?.standingsLists
            ?.firstOrNull()
            ?.constructorStandings
            .orEmpty()
    }

    suspend fun seasonRaces(season: String = "current"): List<JolpicaRace> {
        val envelope = get("$ROOT/$season.json?limit=40")
        return envelope.mrData.raceTable?.races.orEmpty()
    }

    suspend fun raceResults(season: String, round: String): List<JolpicaResult> {
        val envelope = get("$ROOT/$season/$round/results/?limit=40")
        return envelope.mrData.raceTable?.races
            ?.firstOrNull()
            ?.results
            .orEmpty()
    }

    private suspend fun get(url: String): JolpicaEnvelope {
        val request = Request.Builder().url(url).get().build()
        return request.execute(httpClient).parseJsonBody(mrDataAdapter)
    }
}

@JsonClass(generateAdapter = true)
data class JolpicaEnvelope(
    @param:Json(name = "MRData") val mrData: JolpicaMrData
)

@JsonClass(generateAdapter = true)
data class JolpicaMrData(
    @param:Json(name = "RaceTable") val raceTable: JolpicaRaceTable? = null,
    @param:Json(name = "StandingsTable") val standingsTable: JolpicaStandingsTable? = null
)

@JsonClass(generateAdapter = true)
data class JolpicaRaceTable(
    val season: String? = null,
    @param:Json(name = "Races") val races: List<JolpicaRace>? = null
)

@JsonClass(generateAdapter = true)
data class JolpicaRace(
    val season: String,
    val round: String,
    val raceName: String,
    val date: String,
    val time: String? = null,
    @param:Json(name = "Circuit") val circuit: JolpicaCircuit? = null,
    @param:Json(name = "FirstPractice") val firstPractice: JolpicaSessionTime? = null,
    @param:Json(name = "Qualifying") val qualifying: JolpicaSessionTime? = null,
    @param:Json(name = "Sprint") val sprint: JolpicaSessionTime? = null,
    @param:Json(name = "Results") val results: List<JolpicaResult>? = null
)

@JsonClass(generateAdapter = true)
data class JolpicaCircuit(
    val circuitName: String,
    @param:Json(name = "Location") val location: JolpicaLocation? = null
)

@JsonClass(generateAdapter = true)
data class JolpicaLocation(
    val locality: String? = null,
    val country: String? = null
)

@JsonClass(generateAdapter = true)
data class JolpicaSessionTime(
    val date: String,
    val time: String? = null
)

@JsonClass(generateAdapter = true)
data class JolpicaStandingsTable(
    @param:Json(name = "StandingsLists") val standingsLists: List<JolpicaStandingsList>? = null
)

@JsonClass(generateAdapter = true)
data class JolpicaStandingsList(
    @param:Json(name = "DriverStandings") val driverStandings: List<JolpicaDriverStanding>? = null,
    @param:Json(name = "ConstructorStandings") val constructorStandings: List<JolpicaConstructorStanding>? = null
)

@JsonClass(generateAdapter = true)
data class JolpicaDriverStanding(
    val position: String,
    val points: String,
    val wins: String,
    @param:Json(name = "Driver") val driver: JolpicaDriver,
    @param:Json(name = "Constructors") val constructors: List<JolpicaConstructor>? = null
)

@JsonClass(generateAdapter = true)
data class JolpicaConstructorStanding(
    val position: String,
    val points: String,
    val wins: String,
    @param:Json(name = "Constructor") val constructor: JolpicaConstructor
)

@JsonClass(generateAdapter = true)
data class JolpicaDriver(
    val code: String? = null,
    val givenName: String,
    val familyName: String
)

@JsonClass(generateAdapter = true)
data class JolpicaConstructor(
    val name: String
)

@JsonClass(generateAdapter = true)
data class JolpicaResult(
    val position: String,
    val points: String,
    val status: String? = null,
    @param:Json(name = "Driver") val driver: JolpicaDriver,
    @param:Json(name = "Constructor") val constructor: JolpicaConstructor? = null,
    @param:Json(name = "Time") val time: JolpicaResultTime? = null
)

@JsonClass(generateAdapter = true)
data class JolpicaResultTime(
    val time: String? = null
)
