package eu.kanade.tachiyomi.animesource.model

/**
 * The update strategy as Anikku's extension API types it. Dantotsu's [SAnime] keeps
 * [eu.kanade.tachiyomi.source.model.UpdateStrategy] for its own code; an extension built against
 * Anikku sets this one, through [SAnime.setUpdate_strategy].
 */
@Suppress("UNUSED")
enum class AnimeUpdateStrategy {
    /** Included in library updates unless something else excludes it. */
    ALWAYS_UPDATE,

    /** Skipped by library updates: known finished, typically a single episode. */
    ONLY_FETCH_ONCE,
}
