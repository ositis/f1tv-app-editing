package fr.groggy.racecontrol.tv.f1tv

import com.squareup.moshi.JsonClass
import java.util.*

@JsonClass(generateAdapter = true)
data class F1TvChannelResponse(
    val resultObj: F1TvChannelResultObject
)

@JsonClass(generateAdapter = true)
data class F1TvChannelResultObject(
    val containers: List<F1TvChannelContainer>
)

@JsonClass(generateAdapter = true)
data class F1TvChannelContainer(
    val metadata: F1TvChannelMetadata
)

@JsonClass(generateAdapter = true)
data class F1TvChannelMetadata(
    val additionalStreams: List<F1TvChannelAdditionalStream>?
)

@JsonClass(generateAdapter = true)
data class F1TvChannelAdditionalStream(
    val title: String,
    val driverFirstName: String?,
    val driverLastName: String?,
    val racingNumber: Int?,
    val default: Boolean,
    val driverImg: String?,
    val playbackUrl: String,
    val channelId: String,
    val teamName: String?,
    val hex: String?,
    val type: String,
    // 'PRES' = main presentation/commentary, 'WIF' = world interface feed,
    // 'TRACKER' = timing, 'DATA' = data, 'OBC' = onboard camera
    val identifier: String? = null
)

data class F1TvChannelId(val value: String)

sealed class F1TvBasicChannelType {
    object F1Live : F1TvBasicChannelType()
    object Wif : F1TvBasicChannelType()
    object PitLane : F1TvBasicChannelType()
    object Tracker : F1TvBasicChannelType()
    object Data : F1TvBasicChannelType()
    data class Unknown(val type: String, val name: String) : F1TvBasicChannelType()

    companion object {
        /** Trim, uppercase, collapse internal whitespace for feed matching. */
        fun normalizeFeedKey(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.trim().uppercase().replace(Regex("\\s+"), " ")
        }

        /**
         * Prefer stable [identifier] (PRES/WIF/TRACKER/DATA) over localized [name].
         * Title matching includes "F1 LIVE" / "F1LIVE" / PRES.
         */
        fun from(type: String, name: String, identifier: String? = null): F1TvBasicChannelType {
            when (normalizeFeedKey(identifier)) {
                "WIF" -> return Wif
                "PRES", "F1LIVE", "F1 LIVE" -> return F1Live
                "TRACKER" -> return Tracker
                "DATA" -> return Data
                "PIT LANE", "PITLANE", "PLP" -> return PitLane
            }
            val normalizedName = normalizeFeedKey(name)
            if (normalizedName == "F1 LIVE" || normalizedName == "F1LIVE" || normalizedName == "PRES" ||
                normalizedName.contains("F1 LIVE")
            ) {
                return F1Live
            }
            return when (type.lowercase()) {
                "wif" -> Wif
                "additional" -> when (normalizedName) {
                    "F1LIVE", "F1 LIVE", "PRES" -> F1Live
                    "PIT LANE", "PITLANE" -> PitLane
                    "TRACKER" -> Tracker
                    "DATA" -> Data
                    else -> Unknown(type, name)
                }
                else -> Unknown(type, name)
            }
        }
    }
}

sealed class F1TvChannel {
    abstract val channelId: String?
    abstract val contentId: String
}

data class F1TvBasicChannel(
    override val channelId: String?,
    override val contentId: String,
    val type: F1TvBasicChannelType
) : F1TvChannel()

data class F1TvOnboardChannel(
    override val channelId: String,
    override val contentId: String,
    val name: String,
    val background: String?,
    val subTitle: String?,
    val driver: F1TvDriverId
) : F1TvChannel()
