package ani.dantotsu.connections.simkl

import ani.dantotsu.Mapper
import ani.dantotsu.client
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Simkl id resolution — maps an AniList / MyAnimeList id to the Simkl record it belongs to
 * (`GET /search/id`). Needs the api key but no user token.
 *
 * Simkl often stores what AniList splits into several entries (a season, its recap "special", a
 * plan/compilation cut) as **one** record, so two AniList ids can resolve to the same Simkl id.
 * The compare screen uses that to avoid offering the same Simkl entry twice.
 *
 * Hits and misses are cached in [PrefManager] custom vals plus an in-memory negative set.
 */
object SimklApi {
    private const val CACHE_PREFIX = "simkl_id_"
    private const val EP_CACHE_PREFIX = "simkl_eps_"

    private val negativeCache = HashSet<String>()
    private val rateLimiter = Semaphore(4)

    data class Match(val simklId: Long, val totalEpisodes: Int?)

    /** Resolves the Simkl record for an AniList id, falling back to the MyAnimeList id. */
    suspend fun resolve(anilistId: Int?, malId: Int?): Match? {
        anilistId?.let { lookup("anilist", it)?.let { m -> return m } }
        malId?.let { lookup("mal", it)?.let { m -> return m } }
        return null
    }

    private suspend fun lookup(param: String, id: Int): Match? {
        val cacheKey = "$CACHE_PREFIX${param}_$id"
        val cached = PrefManager.getCustomVal(cacheKey, 0L)
        if (cached > 0L) {
            val eps = PrefManager.getCustomVal("$EP_CACHE_PREFIX${param}_$id", 0)
            return Match(cached, eps.takeIf { it > 0 })
        }
        if (cacheKey in negativeCache) return null

        // Distinguish "Simkl has no such id" (cache the miss) from "the request failed" (don't — a
        // 429 or a dropped connection shouldn't blacklist the id for the rest of the session).
        val results: List<IdResult>? = tryWithSuspend {
            rateLimiter.withPermit {
                val raw = client.get(
                    "${Simkl.API_URL}/search/id?$param=$id&client_id=${Simkl.CLIENT_ID}",
                    mapOf("simkl-api-key" to Simkl.CLIENT_ID),
                    cacheTime = 0,
                ).text.trim()
                // Simkl returns a bare object for a hit and (per the docs) an empty array for a miss.
                when {
                    raw.startsWith("[") -> Mapper.json.decodeFromString<List<IdResult>>(raw)
                    raw.startsWith("{") -> listOf(Mapper.json.decodeFromString<IdResult>(raw))
                    else -> emptyList()
                }
            }
        }
        if (results == null) return null

        val match = results.firstOrNull()
        val simklId = match?.ids?.simkl
        if (simklId != null && simklId > 0) {
            PrefManager.setCustomVal(cacheKey, simklId)
            match.totalEpisodes?.let { PrefManager.setCustomVal("$EP_CACHE_PREFIX${param}_$id", it) }
            return Match(simklId, match.totalEpisodes)
        }
        Logger.log("Simkl id miss: $param/$id")
        negativeCache.add(cacheKey)
        return null
    }

    @Serializable
    private data class IdResult(
        @SerialName("total_episodes") val totalEpisodes: Int? = null,
        val ids: Ids? = null,
    ) {
        @Serializable
        data class Ids(val simkl: Long? = null)
    }
}
