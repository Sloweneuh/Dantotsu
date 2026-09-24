package ani.dantotsu.others

import ani.dantotsu.client
import ani.dantotsu.tryWithSuspend
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import kotlinx.serialization.Serializable
import java.net.URLEncoder

object AniSkip {

    /**
     * AniSkip only returns submissions recorded at close to [episodeLength]: a stream carrying a
     * preview or a few seconds of padding misses marks that do exist. So the exact length is asked
     * first, and failing that every submission (`episodeLength=0`), keeping the one recorded
     * closest to this stream's length and dropping any that would run past its end.
     */
    suspend fun getResult(
        malId: Int,
        episodeNumber: Int,
        episodeLength: Long,
        useProxyForTimeStamps: Boolean
    ): List<Stamp>? {
        request(malId, episodeNumber, episodeLength, useProxyForTimeStamps)
            ?.let { return it }
        if (episodeLength <= 0) return null
        val all = request(malId, episodeNumber, 0, useProxyForTimeStamps) ?: return null
        return all.groupBy { it.skipType }
            .mapNotNull { (_, stamps) ->
                stamps.filter { it.interval.endTime <= episodeLength }
                    .minByOrNull { kotlin.math.abs(it.episodeLength - episodeLength) }
            }
            .takeIf { it.isNotEmpty() }
    }

    private suspend fun request(
        malId: Int,
        episodeNumber: Int,
        episodeLength: Long,
        useProxyForTimeStamps: Boolean
    ): List<Stamp>? {
        val url =
            "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber?types[]=ed&types[]=mixed-ed&types[]=mixed-op&types[]=op&types[]=recap&episodeLength=$episodeLength"
        return tryWithSuspend {
            val a = if (useProxyForTimeStamps)
                client.get(
                    "https://corsproxy.io/?${
                        URLEncoder.encode(url, "utf-8").replace("+", "%20")
                    }"
                )
            else
                client.get(url)
            val res = a.parsed<AniSkipResponse>()
            if (res.found) res.results?.takeIf { it.isNotEmpty() } else null
        }
    }

    /**
     * Marks an extension attached to its videos, in the player's shape. Sources that publish their
     * own intro/outro times have them from the day an episode goes up, which AniSkip — waiting on
     * someone to submit them — often does not. `Other` chapters are left out: they are not
     * something the player knows how to offer skipping.
     */
    fun fromSource(timestamps: List<TimeStamp>?): List<Stamp> =
        timestamps.orEmpty().mapNotNull { ts ->
            val type = when (ts.type) {
                ChapterType.Opening -> "op"
                ChapterType.Ending -> "ed"
                ChapterType.Recap -> "recap"
                ChapterType.MixedOp -> "mixed-op"
                ChapterType.Other -> return@mapNotNull null
            }
            if (ts.end <= ts.start) return@mapNotNull null
            Stamp(AniSkipInterval(ts.start, ts.end), type, "source", 0.0)
        }

    @Serializable
    data class AniSkipResponse(
        val found: Boolean,
        val results: List<Stamp>?,
        val message: String?,
        val statusCode: Int
    )

    @Serializable
    data class Stamp(
        val interval: AniSkipInterval,
        val skipType: String,
        val skipId: String,
        val episodeLength: Double
    ) : java.io.Serializable


    fun String.getType(): String {
        return when (this) {
            "op" -> "Opening"
            "ed" -> "Ending"
            "recap" -> "Recap"
            "mixed-ed" -> "Mixed Ending"
            "mixed-op" -> "Mixed Opening"
            else -> this
        }
    }

    @Serializable
    data class AniSkipInterval(
        val startTime: Double,
        val endTime: Double
    ) : java.io.Serializable
}