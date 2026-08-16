package ani.dantotsu.connections.comick

import ani.dantotsu.connections.malsync.MalSyncApi
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Comick as a source of episode titles and synopses, alongside Kitsu, Anify and Jikan.
 *
 * Comick has no thumbnails (its episode rows carry an empty `previews` array), so this only ever
 * contributes text — the watch screen merges it *under* the providers that do supply images.
 * Coverage is user-contributed and uneven: currently-airing shows are usually complete up to the
 * latest aired episode, while plenty of finished series have no episode rows at all.
 */
object ComickEpisodes {

    /** Pref key holding a user-chosen Comick anime slug for an AniList id. */
    fun savedSlugKey(anilistId: Int) = "comick_anime_slug_$anilistId"

    /**
     * Pref key for an automatically resolved slug. Kept apart from [savedSlugKey] so that
     * unlinking a hand-picked entry doesn't silently resurrect it from the cache — and so a cached
     * guess never outranks the user's choice.
     */
    private fun autoSlugKey(anilistId: Int) = "comick_anime_slug_auto_$anilistId"

    /**
     * Remember what a match resolved to, including that it resolved to nothing. Matching costs a
     * search plus a detail fetch per candidate, which is far too much to repeat every time the
     * watch screen opens, and a miss is just as worth remembering as a hit.
     */
    fun cacheAutoSlug(anilistId: Int, slug: String?) {
        PrefManager.setCustomVal(autoSlugKey(anilistId), slug ?: "")
    }

    /** Forget a remembered match so the next lookup searches again. */
    fun clearAutoSlug(anilistId: Int) {
        PrefManager.removeCustomVal(autoSlugKey(anilistId))
    }

    /**
     * Resolve the Comick anime slug for [media], preferring a slug the user pinned by hand.
     * @return the slug, or null when nothing matched
     */
    suspend fun resolveSlug(media: Media): String? = withContext(Dispatchers.IO) {
        PrefManager.getNullableCustomVal<String>(
            savedSlugKey(media.id), null, String::class.java
        )?.takeIf { it.isNotBlank() }?.let { return@withContext it }

        // An empty cached value is a remembered miss, not a cache absence — return without
        // re-running the search.
        PrefManager.getNullableCustomVal<String>(
            autoSlugKey(media.id), null, String::class.java
        )?.let { return@withContext it.takeIf { cached -> cached.isNotBlank() } }

        val titles = buildList {
            media.name?.takeIf { it.isNotBlank() }?.let { add(it) }
            media.nameRomaji.takeIf { it.isNotBlank() && it !in this }?.let { add(it) }
            media.synonyms.forEach { synonym ->
                if (synonym.isNotBlank() && !hasCJK(synonym) && synonym !in this) add(synonym)
            }
        }
        if (titles.isEmpty()) return@withContext null

        // MalSync knows Comick ids for plenty of entries; when it answers, those slugs are checked
        // before the title search, exactly as the manga path does. It is best-effort only — a slow
        // or failing MalSync must not hold up the match.
        val malSyncSlugs = if (PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled)) {
            val mode = PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both"
            if (mode == "both" || mode == "anime") {
                try {
                    withTimeout(10000L) {
                        MalSyncApi.getQuicklinks(media.id, media.idMAL, "anime")
                    }?.Sites?.entries
                        ?.firstOrNull { it.key.contains("comick", true) }
                        ?.value?.values?.mapNotNull { it.identifier }
                } catch (e: TimeoutCancellationException) {
                    Logger.log("Comick episodes: MalSync timed out")
                    null
                } catch (e: Exception) {
                    Logger.log("Comick episodes: MalSync error: ${e.message}")
                    null
                }
            } else null
        } else null

        val externalLinks = media.externalLinks.mapNotNull { it.getOrNull(1) }

        ComickApi.searchAndMatchAnime(
            titles = titles,
            anilistId = media.id,
            malId = media.idMAL,
            malSyncSlugs = malSyncSlugs?.takeIf { it.isNotEmpty() },
            externalLinks = externalLinks.takeIf { it.isNotEmpty() }
        ).also { cacheAutoSlug(media.id, it) }
    }

    /**
     * Episode metadata for [media], keyed by episode number so it lines up with the other
     * providers' maps.
     *
     * @return the map, or null when no Comick entry matched or it has no episodes listed
     */
    suspend fun getEpisodeDetails(media: Media): Map<String, Episode>? = withContext(Dispatchers.IO) {
        if (!PrefManager.getVal<Boolean>(PrefName.ComickEnabled)) return@withContext null
        if (media.anime == null) return@withContext null

        val slug = resolveSlug(media) ?: return@withContext null
        Logger.log("Comick episodes: matched ${media.mainName()} -> $slug")

        val episodes = ComickApi.getEpisodes(slug)
        if (episodes.isEmpty()) {
            Logger.log("Comick episodes: $slug has no episodes listed")
            return@withContext null
        }

        episodes.mapNotNull { episode ->
            val number = episode.number() ?: return@mapNotNull null
            val title = episode.displayTitle()
            val desc = episode.anime_episode_profiles?.synopsis?.takeIf { it.isNotBlank() }
            if (title == null && desc == null) return@mapNotNull null
            number to Episode(number = number, title = title, desc = desc)
        }.toMap().takeIf { it.isNotEmpty() }
    }
}
