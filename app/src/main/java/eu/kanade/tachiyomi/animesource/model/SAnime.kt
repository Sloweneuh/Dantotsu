package eu.kanade.tachiyomi.animesource.model

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import java.io.Serializable

interface SAnime : Serializable {

    var url: String

    var title: String

    var artist: String?

    var author: String?

    var description: String?

    var genre: String?

    var status: Int

    var thumbnail_url: String?

    /** A wide backdrop, when the source has one. @since extensions-lib 16 */
    var background_url: String?

    var update_strategy: UpdateStrategy

    /**
     * Whether this anime lists episodes or seasons (each season an [SAnime] of its own).
     * @since extensions-lib 16
     */
    var fetch_type: FetchType

    /** This season's number, when the anime is a season; -1 otherwise. @since extensions-lib 16 */
    var season_number: Double

    var initialized: Boolean

    /**
     * The update strategy as Anikku's extension API types it ([AnimeUpdateStrategy]), for an
     * extension built against it; Dantotsu's own code uses [update_strategy].
     */
    fun setUpdate_strategy(strategy: AnimeUpdateStrategy) {
        update_strategy = UpdateStrategy.valueOf(strategy.name)
    }

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct()
    }

    fun copyFrom(other: SAnime) {
        if (other.author != null) {
            author = other.author
        }

        if (other.artist != null) {
            artist = other.artist
        }

        if (other.description != null) {
            description = other.description
        }

        if (other.genre != null) {
            genre = other.genre
        }

        if (other.thumbnail_url != null) {
            thumbnail_url = other.thumbnail_url
        }

        status = other.status

        if (other.background_url != null) {
            background_url = other.background_url
        }

        update_strategy = other.update_strategy

        fetch_type = other.fetch_type

        season_number = other.season_number

        if (!initialized) {
            initialized = other.initialized
        }
    }

    fun copy() = create().also {
        it.url = url
        it.title = title
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre
        it.status = status
        it.thumbnail_url = thumbnail_url
        it.background_url = background_url
        it.update_strategy = update_strategy
        it.fetch_type = fetch_type
        it.season_number = season_number
        it.initialized = initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SAnime {
            return SAnimeImpl()
        }

        /** Builds one in a call — the factory Anikku's extension API offers. */
        operator fun invoke(
            url: String,
            title: String,
            artist: String? = null,
            author: String? = null,
            description: String? = null,
            genre: String? = null,
            status: Int = 0,
            thumbnail_url: String? = null,
            background_url: String? = null,
            fetch_type: FetchType = FetchType.Episodes,
            season_number: Double = -1.0,
            update_strategy: AnimeUpdateStrategy = AnimeUpdateStrategy.ALWAYS_UPDATE,
            initialized: Boolean = false,
        ): SAnime = create().also {
            it.url = url
            it.title = title
            it.artist = artist
            it.author = author
            it.description = description
            it.genre = genre
            it.status = status
            it.thumbnail_url = thumbnail_url
            it.background_url = background_url
            it.fetch_type = fetch_type
            it.season_number = season_number
            it.setUpdate_strategy(update_strategy)
            it.initialized = initialized
        }

        private const val serialVersionUID = 1L
    }
}
