package eu.kanade.tachiyomi.animesource.model

/**
 * What an [SAnime] lists: its episodes, or the seasons that hold them. Fixed once the anime has been
 * initialised.
 *
 * @since extensions-lib 16
 */
enum class FetchType {
    /** The anime only lists seasons ([eu.kanade.tachiyomi.animesource.AnimeSource.getSeasonList]). */
    Seasons,

    /** The anime only lists episodes ([eu.kanade.tachiyomi.animesource.AnimeSource.getEpisodeList]). */
    Episodes,
}
