package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode

/**
 * A source that can open an [SAnime] or [SEpisode] from a link to its site. Dantotsu doesn't open
 * links through extensions; this is here so an extension built against it loads.
 *
 * @since extensions-lib 1.5
 */
interface ResolvableAnimeSource : AnimeSource {

    /** What [uri] would open, or [UriType.Unknown] when this source can't tell. */
    fun getUriType(uri: String): UriType

    /** The anime [uri] opens, when [getUriType] said [UriType.Anime]. */
    suspend fun getAnime(uri: String): SAnime?

    /** The episode [uri] opens, when [getUriType] said [UriType.Episode]. */
    suspend fun getEpisode(uri: String): SEpisode?
}

sealed interface UriType {
    data object Anime : UriType
    data object Episode : UriType
    data object Unknown : UriType
}
