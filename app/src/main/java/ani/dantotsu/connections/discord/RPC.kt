package ani.dantotsu.connections.discord

/**
 * Shared Rich Presence data types used by [RPCManager] and calling screens.
 */
@Suppress("MemberVisibilityCanBePrivate")
class RPC {

    enum class Type {
        PLAYING, STREAMING, LISTENING, WATCHING, COMPETING
    }

    data class Link(val label: String, val url: String)

    /**
     * What a presence is about. Also identifies which screen owns it: each kind is set by one
     * screen, and [RPCManager.clearPresence] only clears a presence of the kind it is given.
     */
    enum class Kind {
        ANIME, MANGA, NOVEL,

        /** An OP/ED playing from the media page. Never replaces a video or reading presence. */
        MUSIC,

        /** Looking at a media page. Opt-in; shown only while nothing else is. */
        BROWSING,
    }

    /** A site media can come from — what the media button links to and the small icon shows. */
    enum class Site(val label: String) {
        ANILIST("AniList"),
        MAL("MyAnimeList"),
        MANGAUPDATES("MangaUpdates"),
        KITSU("Kitsu"),
        SIMKL("Simkl"),
        MANGABAKA("MangaBaka"),
        COMICK("Comick"),

        /** An extension's own source; [Source.name] and [Source.iconUrl] carry which one. */
        EXTENSION("Extension"),
    }

    /**
     * Where the media on show comes from: the site, the media's page on it, and — for an
     * extension, whose icon isn't one of [RPCManager]'s — the icon to show.
     */
    data class Source(
        val site: Site,
        val url: String?,
        val name: String = site.label,
        val iconUrl: String? = null,
        /** "Anime", "Manga" or "Novel", for the button: "View Novel on AniList". */
        val noun: String? = null,
    ) {
        /** The media button's label, kept within Discord's 32 characters. */
        val buttonLabel: String
            get() = listOfNotNull(
                noun?.let { "View $it on $name" },
                "View on $name",
                noun?.let { "View $it" },
            ).firstOrNull { it.length <= 32 } ?: "View Page"
    }

    companion object {
        data class RPCData(
            val applicationId: String,
            val kind: Kind,
            val type: Type? = null,
            val activityName: String? = null,
            val details: String? = null,
            val state: String? = null,
            val largeImage: Link? = null,
            val smallImage: Link? = null,
            val status: String? = null,
            val startTimestamp: Long? = null,
            val stopTimestamp: Long? = null,
            /** Paused playback: the presence clears itself if it stays paused. */
            val isPaused: Boolean = false,
            /** Where the media comes from, for the media button, the site icon and the profile button. */
            val source: Source? = null,
        )
    }
}
