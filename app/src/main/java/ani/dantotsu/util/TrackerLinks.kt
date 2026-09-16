package ani.dantotsu.util

/**
 * AniList and MangaUpdates links as they turn up in text that came from somewhere else — an
 * extension's synopsis, another tracker's description. Aggregators often cite the tracker entry
 * there, which is as good a match as an API cross-id when the API has none.
 */
object TrackerLinks {

    /** An `anilist.co/anime/<id>` or `anilist.co/manga/<id>` link. */
    data class AnilistMedia(val id: Int, val isAnime: Boolean)

    private val anilistMedia =
        Regex("""https?://(?:www\.)?anilist\.co/(anime|manga)/(\d+)""", RegexOption.IGNORE_CASE)

    // Both shapes MangaUpdates uses: /series/<base36 id or slug> and the older series.html?id=<n>.
    private val muSeries = Regex(
        """https?://(?:www\.)?mangaupdates\.com/series(?:/([A-Za-z0-9]+)|\.html\?id=(\d+))""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The first AniList media link in [text] of the kind asked for. The kind matters: a manga's
     * synopsis can just as well cite its anime adaptation, and a button (or an extension link, see
     * [ani.dantotsu.settings.ExtensionMediaLinker]) built from that would point at the wrong entry.
     */
    fun findAnilistMedia(text: String?, isAnime: Boolean): AnilistMedia? {
        if (text.isNullOrBlank()) return null
        return anilistMedia.findAll(text)
            .mapNotNull { m ->
                m.groupValues[2].toIntOrNull()
                    ?.let { AnilistMedia(it, m.groupValues[1].equals("anime", ignoreCase = true)) }
            }
            .firstOrNull { it.isAnime == isAnime }
    }

    /** The first MangaUpdates series link in [text], normalized to the `/series/<slugOrId>` form. */
    fun findMangaUpdatesSeries(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = muSeries.find(text) ?: return null
        val slugOrId = match.groupValues[1].ifEmpty { match.groupValues[2] }
        return "https://www.mangaupdates.com/series/$slugOrId"
    }
}
