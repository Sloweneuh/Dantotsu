package ani.dantotsu.connections.animethemes

import android.content.Context
import ani.dantotsu.Mapper
import ani.dantotsu.R
import ani.dantotsu.client
import ani.dantotsu.tryWithSuspend
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString

/**
 * AnimeThemes (animethemes.moe) — the openings, endings and insert songs of an anime.
 *
 * This replaces the OP/ED lines Dantotsu used to scrape off MyAnimeList, which were one
 * unparsed sentence per theme ("1: \"again\" by YUI (eps 1-14)") whose only affordance was a
 * YouTube search link. AnimeThemes hands over the same facts as fields — song, artists, which
 * episodes each version covers — plus the thing MAL never had: the theme itself, as a playable
 * webm and a matching audio track.
 *
 * Public GraphQL, no key and no auth. One query per media, keyed off the AniList id via the
 * API's own `findAnimeByExternalSite`, falling back to the MAL id for the handful of entries
 * mapped only that way.
 */
object AnimeThemes {

    private const val API_URL = "https://graphql.animethemes.moe"
    const val SITE_URL = "https://animethemes.moe"

    /**
     * Themes for one anime, ordered the way the site orders them (OP1, OP2, ED1, ...).
     * Null only when both lookups failed; an anime AnimeThemes has not catalogued returns empty.
     */
    suspend fun getThemes(anilistId: Int?, malId: Int?): ArrayList<AnimeThemeTrack>? {
        val byAnilist = anilistId?.let { fetch("ANILIST", it) }
        if (!byAnilist.isNullOrEmpty()) return byAnilist
        // Null here means the request itself failed, not that the anime is uncatalogued — the
        // MAL lookup goes to the same server and would only fail the same way, a second time.
        if (anilistId != null && byAnilist == null) return null
        val byMal = malId?.let { fetch("MAL", it) }
        return byMal ?: byAnilist
    }

    /**
     * Bounded well below OkHttp's own timeouts: when AnimeThemes' origin is down, Cloudflare holds
     * each request about twenty seconds before answering 522, and nothing is worth that wait.
     */
    private const val REQUEST_TIMEOUT_MS = 8_000L

    private suspend fun fetch(site: String, id: Int): ArrayList<AnimeThemeTrack>? =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { request(site, id) }

    private suspend fun request(site: String, id: Int): ArrayList<AnimeThemeTrack>? =
        tryWithSuspend(snackbar = false) {
            // The body is handed over as a RequestBody rather than through `json`: AnimeThemes
            // rejects anything that does not arrive as application/json, and this is the one
            // path that carries the media type all the way down.
            val body = Mapper.json.encodeToString(GraphQLBody(query(site, id)))
                .toRequestBody("application/json".toMediaType())
            val res = client.post(
                API_URL,
                headers = mapOf("Accept" to "application/json"),
                requestBody = body
            )
            if (!res.isSuccessful) return@tryWithSuspend null
            val anime = Mapper.parse<Response>(res.text).data?.anime ?: return@tryWithSuspend null
            anime.flatMap { node ->
                node.animethemes.orEmpty().mapNotNull { it.toTrack(node.slug) }
            }.toCollection(ArrayList())
        }

    /**
     * `id` is an Int and `site` a fixed enum name, so both are inlined rather than sent as
     * GraphQL variables — it keeps the request body to a single field.
     */
    private fun query(site: String, id: Int) = """
        {
          findAnimeByExternalSite(site: $site, id: [$id]) {
            slug
            animethemes {
              type
              sequence
              slug
              group { name }
              song {
                title { romaji }
                performances { alias as artist { name { main } } }
              }
              animethemeentries {
                episodes
                notes
                nsfw
                spoiler
                version
                videos { nodes { resolution nc subbed lyrics overlap source tags link audio { link } } }
              }
            }
          }
        }
    """.trimIndent()

    private fun ThemeNode.toTrack(animeSlug: String?): AnimeThemeTrack? {
        val slug = slug ?: return null
        val artists = song?.performances.orEmpty().mapNotNull { performance ->
            val name = performance.artist?.name?.main ?: performance.alias ?: return@mapNotNull null
            // `as` is who the artist performed as — a character name, usually with the seiyuu
            // credited behind it. Worth keeping: it is how these songs are credited on covers.
            performance.performedAs?.takeIf { it.isNotBlank() }?.let { "$name as $it" } ?: name
        }
        val versions = animethemeentries.orEmpty().map { entry ->
            AnimeThemeVersion(
                version = entry.version,
                episodes = entry.episodes?.takeIf { it.isNotBlank() },
                notes = entry.notes?.takeIf { it.isNotBlank() },
                spoiler = entry.spoiler == true,
                nsfw = entry.nsfw == true,
                videos = entry.videos?.nodes.orEmpty().mapNotNull { it.toVideo() }
                    // Best copy first: the sheet plays videos.first() and lists the rest.
                    .sortedWith(compareByDescending<AnimeThemeVideo> { it.resolution ?: 0 }
                        .thenByDescending { it.nc })
            )
        }.filter { it.videos.isNotEmpty() }
        if (versions.isEmpty()) return null
        return AnimeThemeTrack(
            slug = slug,
            type = type ?: slug.takeWhile { it.isLetter() },
            sequence = sequence,
            group = group?.name?.takeIf { it.isNotBlank() },
            song = song?.title?.romaji?.takeIf { it.isNotBlank() },
            artists = artists,
            versions = versions,
            animeSlug = animeSlug
        )
    }

    private fun VideoNode.toVideo(): AnimeThemeVideo? {
        val link = link?.takeIf { it.isNotBlank() } ?: return null
        return AnimeThemeVideo(
            link = link,
            audio = audio?.link?.takeIf { it.isNotBlank() },
            resolution = resolution,
            source = source?.takeIf { it.isNotBlank() && it != "UNKNOWN" },
            nc = nc == true,
            subbed = subbed == true,
            lyrics = lyrics == true,
            overlap = overlap?.takeIf { it.isNotBlank() && it != "NONE" },
            tags = tags?.takeIf { it.isNotBlank() }
        )
    }

    @Serializable
    private data class GraphQLBody(val query: String)

    @Serializable
    private data class Response(val data: Data? = null) {
        @Serializable
        data class Data(@SerialName("findAnimeByExternalSite") val anime: List<AnimeNode>? = null)
    }

    @Serializable
    private data class AnimeNode(
        val slug: String? = null,
        val animethemes: List<ThemeNode>? = null
    )

    @Serializable
    private data class ThemeNode(
        val type: String? = null,
        val sequence: Int? = null,
        val slug: String? = null,
        val group: GroupNode? = null,
        val song: SongNode? = null,
        val animethemeentries: List<EntryNode>? = null
    )

    @Serializable
    private data class GroupNode(val name: String? = null)

    @Serializable
    private data class SongNode(
        val title: TitleNode? = null,
        val performances: List<PerformanceNode>? = null
    ) {
        @Serializable
        data class TitleNode(val romaji: String? = null)
    }

    @Serializable
    private data class PerformanceNode(
        val alias: String? = null,
        @SerialName("as") val performedAs: String? = null,
        val artist: ArtistNode? = null
    ) {
        @Serializable
        data class ArtistNode(val name: NameNode? = null) {
            @Serializable
            data class NameNode(val main: String? = null)
        }
    }

    @Serializable
    private data class EntryNode(
        val episodes: String? = null,
        val notes: String? = null,
        val nsfw: Boolean? = null,
        val spoiler: Boolean? = null,
        val version: Int? = null,
        val videos: VideoConnection? = null
    ) {
        @Serializable
        data class VideoConnection(val nodes: List<VideoNode>? = null)
    }

    @Serializable
    private data class VideoNode(
        val resolution: Int? = null,
        val nc: Boolean? = null,
        val subbed: Boolean? = null,
        val lyrics: Boolean? = null,
        val overlap: String? = null,
        val source: String? = null,
        val tags: String? = null,
        val link: String? = null,
        val audio: AudioNode? = null
    ) {
        @Serializable
        data class AudioNode(val link: String? = null)
    }
}

/**
 * One theme of an anime: "OP1", its song, and every version of it that was aired.
 *
 * A theme has more than one version when the sequence was re-cut mid-run — a new episode range,
 * sometimes different footage — which is why [versions] is a list and not a single video.
 */
data class AnimeThemeTrack(
    /** "OP1", "ED1-TV" — the label AnimeThemes and the fandom both use. */
    val slug: String,
    /** OP, ED or IN. */
    val type: String,
    val sequence: Int?,
    /** Set when the theme belongs to a named variant of the run, e.g. a dub or a recap cut. */
    val group: String?,
    val song: String?,
    val artists: List<String>,
    val versions: List<AnimeThemeVersion>,
    val animeSlug: String?
) : java.io.Serializable {

    val isOpening get() = type.equals("OP", true)
    val isEnding get() = type.equals("ED", true)

    /** "again — YUI", or just the slug for the rare theme with no song on file. */
    fun displayTitle(): String {
        val title = song ?: return slug
        return if (artists.isEmpty()) title else "$title — ${artists.joinToString(", ")}"
    }
}

data class AnimeThemeVersion(
    val version: Int?,
    /** The episodes this cut ran over, as AnimeThemes writes them: "1-14", "17-27", "1-14, 16". */
    val episodes: String?,
    val notes: String?,
    val spoiler: Boolean,
    val nsfw: Boolean,
    val videos: List<AnimeThemeVideo>
) : java.io.Serializable {

    /** "Ep. 28" when the cut ran over one episode, "Eps. 1-14, 16" when it ran over several. */
    fun episodesLabel(context: Context): String? {
        val episodes = episodes ?: return null
        val single = !episodes.contains('-') && !episodes.contains(',')
        return context.getString(
            if (single) R.string.theme_episode else R.string.theme_episodes,
            episodes
        )
    }
}

data class AnimeThemeVideo(
    val link: String,
    val audio: String?,
    val resolution: Int?,
    /** BD, WEB, DVD, RAW... where the rip came from. */
    val source: String?,
    /** No credits over the footage. */
    val nc: Boolean,
    val subbed: Boolean,
    val lyrics: Boolean,
    /** TRANS or OVER when the sequence bleeds into the episode; null when it stands alone. */
    val overlap: String?,
    /** AnimeThemes' own tag for this copy ("NCBD1080"); kept as the canonical filename suffix. */
    val tags: String?
) : java.io.Serializable {

    /**
     * What tells two copies of the same theme apart, spelled out: "1080p Blu-ray (no credits)".
     *
     * AnimeThemes writes this as one tag — "NCBD1080" is NC for no credits, BD for the source,
     * 1080 for the height — which is second nature on the site and opaque everywhere else, so
     * the structured fields behind it are used instead.
     */
    fun label(context: Context): String {
        val head = listOfNotNull(resolution?.let { "${it}p" }, sourceName(context))
            .joinToString(" ")
            .ifBlank { context.getString(R.string.theme_video) }
        val notes = listOfNotNull(
            if (nc) context.getString(R.string.theme_no_credits) else null,
            // TRANS/OVER: the sequence runs over the episode's own footage rather than over
            // animation made for it.
            if (overlap != null) context.getString(R.string.theme_overlap) else null,
            if (subbed) context.getString(R.string.theme_subbed) else null,
            if (lyrics) context.getString(R.string.theme_lyrics) else null
        )
        return if (notes.isEmpty()) head else "$head (${notes.joinToString(", ")})"
    }

    /** BD, WEB and RAW are what AnimeThemes records; only the first needs expanding. */
    private fun sourceName(context: Context): String? = when (source?.uppercase()) {
        null -> null
        "BD" -> context.getString(R.string.theme_source_bd)
        "WEB" -> context.getString(R.string.theme_source_web)
        "RAW" -> context.getString(R.string.theme_source_raw)
        else -> source
    }
}
