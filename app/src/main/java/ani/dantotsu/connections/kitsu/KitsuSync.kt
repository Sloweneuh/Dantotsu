package ani.dantotsu.connections.kitsu

import ani.dantotsu.Mapper
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.okHttpClient
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One-way list synchronisation to Kitsu (anime **and** manga).
 *
 * Whenever the user updates an entry on AniList (or MangaUpdates, when logged in), the same state is
 * pushed to their Kitsu library. Nothing is pulled back — Kitsu is a destination only.
 *
 * Kitsu has no bulk list-write route: every entry is a `POST`/`PATCH`/`DELETE` on
 * `/library-entries`. The matching Kitsu media is discovered through Kitsu's own `/mappings` route
 * (see [KitsuApi]); MangaUpdates entries go through MangaBaka's cross-source mapping first
 * ([MangaBakaApi.getCrossIdsFromMangaUpdates]) since Kitsu doesn't know MangaUpdates ids.
 *
 * Volumes are not synced — Kitsu's library entry has no "volumes read" field.
 */
object KitsuSync {
    private val JSON_API = "application/vnd.api+json".toMediaTypeOrNull()

    /**
     * Tolerant decoder for the big library payload: Kitsu sprinkles nulls where a value is expected
     * (e.g. a null `progress`, an unknown enum), and one such value shouldn't blow up the whole
     * paginated fetch. [Mapper] can't set [Json.coerceInputValues] globally without risk.
     */
    internal val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Suspends because of the [Kitsu.token] test: the token lives in memory and is restored in the
     * background, so asking before the restore has finished answers "not signed in" and every push
     * guarded by this quietly does nothing. Waiting here rather than at each caller means no write
     * path can be added later that forgets to. See [TrackerSessions].
     */
    suspend fun isEnabled(force: Boolean = false): Boolean {
        TrackerSessions.await()
        return Kitsu.token != null && (force || PrefManager.getVal(PrefName.KitsuListSyncEnabled))
    }

    // ---- pushes ----

    suspend fun syncFromAnilist(
        isAnime: Boolean,
        anilistId: Int?,
        malId: Int?,
        status: String?,
        progress: Int?,
        score: Int?,
        startDate: FuzzyDate? = null,
        finishDate: FuzzyDate? = null,
        force: Boolean = false,
    ): Boolean {
        if (!isEnabled(force)) return false
        val mediaId = KitsuApi.resolveMediaId(isAnime, anilistId, malId) ?: return false
        return upsert(
            isAnime, mediaId,
            EntryAttributes(
                status = mapAnilistStatus(status),
                progress = progress,
                ratingTwenty = toRatingTwenty(score),
                // Always explicit: a null would let a stale reconsuming=true survive, and
                // toCanon() reads that as REPEATING forever regardless of status (a ghost diff).
                reconsuming = status == "REPEATING",
                startedAt = toIsoDate(startDate),
                finishedAt = toIsoDate(finishDate),
            ),
        )
    }

    suspend fun syncFromMangaUpdates(
        muSeriesId: Long?,
        muListId: Int?,
        progress: Int?,
        startDate: FuzzyDate? = null,
        force: Boolean = false,
    ): Boolean {
        if (!isEnabled(force)) return false
        val id = muSeriesId ?: return false
        val mediaId = resolveMangaFromMu(id) ?: return false
        return upsert(
            false, mediaId,
            EntryAttributes(
                status = mapMangaUpdatesList(muListId),
                progress = progress,
                startedAt = toIsoDate(startDate),
            ),
        )
    }

    /** MU → Kitsu manga id: Kitsu's own `mangaupdates` mapping first, then via MangaBaka's cross-ids. */
    suspend fun resolveMangaFromMu(muSeriesId: Long): String? {
        KitsuApi.resolveMangaFromMangaUpdates(muSeriesId)?.let { return it }
        val cross = MangaBakaApi.getCrossIdsFromMangaUpdates(muSeriesId)
        return KitsuApi.resolveMediaId(false, cross.anilistId, cross.malId)
    }

    suspend fun deleteFromAnilist(isAnime: Boolean, anilistId: Int?, malId: Int?, force: Boolean = false): Boolean {
        if (!isEnabled(force)) return false
        val mediaId = KitsuApi.resolveMediaId(isAnime, anilistId, malId) ?: return false
        val entryId = findEntryId(isAnime, mediaId) ?: return true // nothing to remove
        return deleteByEntryId(entryId, force = true)
    }

    suspend fun deleteFromMangaUpdates(muSeriesId: Long?, force: Boolean = false): Boolean {
        if (!isEnabled(force)) return false
        val id = muSeriesId ?: return false
        val mediaId = resolveMangaFromMu(id) ?: return false
        val entryId = findEntryId(false, mediaId) ?: return true
        return deleteByEntryId(entryId, force = true)
    }

    /** Removes a library entry by its Kitsu entry id. [force] bypasses the list-sync toggle. */
    suspend fun deleteByEntryId(entryId: String?, force: Boolean = false): Boolean {
        if (!isEnabled(force)) return false
        val id = entryId ?: return false
        return tryWithSuspend {
            val request = authed("${Kitsu.API_URL}/library-entries/$id").delete().build()
            val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
            val ok = response.isSuccessful || response.code == 404
            response.close()
            ok
        } ?: false
    }

    /** PATCHes an existing entry, or POSTs a new one when the user has none for this media. */
    private suspend fun upsert(isAnime: Boolean, mediaId: String, rawAttrs: EntryAttributes): Boolean {
        // Kitsu rejects progress past the media's own episode/chapter total (its count can differ
        // from AniList's). Clamp it so the write lands instead of 400-ing.
        val total = KitsuApi.mediaTotal(isAnime, mediaId)
        val attrs = if (total != null && total > 0 && (rawAttrs.progress ?: 0) > total)
            rawAttrs.copy(progress = total) else rawAttrs
        val existing = findEntryId(isAnime, mediaId)
        return if (existing != null) {
            send("${Kitsu.API_URL}/library-entries/$existing", "PATCH",
                EntryBody(EntryData(id = existing, attributes = attrs)))
        } else {
            val userId = Kitsu.userid ?: return false
            send("${Kitsu.API_URL}/library-entries", "POST",
                EntryBody(EntryData(
                    attributes = attrs,
                    relationships = EntryRelationships(
                        user = Rel(RelData("users", userId)),
                        media = Rel(RelData(if (isAnime) "anime" else "manga", mediaId)),
                    ),
                )))
        }
    }

    /**
     * Headers for reading the signed-in user's *own* library.
     *
     * These reads used to go out with nothing but an `Accept` header, i.e. as an anonymous caller.
     * Kitsu answers those with only the entries a stranger is allowed to see, so a library whose
     * privacy is set on Kitsu comes back as an empty list — HTTP 200, no error, nothing to catch —
     * and the comparison then reports every single title as missing from Kitsu while it sits there
     * plainly on the site. Writes were authenticated all along ([authed]); only the reads weren't.
     */
    private fun readHeaders(): Map<String, String> {
        val base = mapOf("Accept" to "application/vnd.api+json")
        return Kitsu.token?.let { base + ("Authorization" to "Bearer $it") } ?: base
    }

    private suspend fun findEntryId(isAnime: Boolean, mediaId: String): String? = tryWithSuspend {
        val userId = Kitsu.userid ?: return@tryWithSuspend null
        val url = "${Kitsu.API_URL}/library-entries" +
            "?filter%5BuserId%5D=$userId" +
            "&filter%5BmediaId%5D=$mediaId" +
            "&filter%5Bkind%5D=${if (isAnime) "anime" else "manga"}"
        // cacheTime = 0: the shared client caches for six hours by default, and an "is there
        // already an entry?" check that reads a stale answer makes [upsert] POST a duplicate over
        // an entry that exists.
        json.decodeFromString<LibraryResponse>(
            ani.dantotsu.client.get(url, readHeaders(), cacheTime = 0).text
        ).data?.firstOrNull()?.id
    }

    private suspend fun send(url: String, method: String, body: EntryBody): Boolean = tryWithSuspend {
        val payload = Mapper.json.encodeToString(body)
        val request = authed(url).method(method, payload.toRequestBody(JSON_API)).build()
        val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        val code = response.code
        if (!response.isSuccessful) {
            Logger.log("Kitsu $method $url: HTTP $code — sent $payload — got ${response.body?.string()?.take(400)}")
        }
        response.close()
        code in 200..299
    } ?: false

    /** Content-Type is set by the request body's media type — don't add it here (a duplicate 400s). */
    private fun authed(url: String): Request.Builder {
        val token = Kitsu.token ?: ""
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.api+json")
    }

    // ---- snapshot for the compare screen ----

    /** One entry in the user's Kitsu library, flattened for the comparison. */
    data class LibraryEntry(
        val entryId: String,
        val mediaId: String,
        val status: String?,
        val reconsuming: Boolean,
        val progress: Int,
        val ratingTwenty: Int?,
        val startedAt: String?,
        val finishedAt: String?,
        val title: String?,
        val coverUrl: String?,
        /** Kitsu's own episode/chapter total for this media (0 = unknown). */
        val total: Int,
        /** Cross-source ids Kitsu holds for this media, from the embedded mappings. */
        val anilistId: Int? = null,
        val malId: Int? = null,
    )

    data class Snapshot(val entries: List<LibraryEntry>, val counts: Map<String, Int>)

    /**
     * The user's whole Kitsu library for one media kind, plus exact per-status counts from the
     * response `meta.statusCounts` (reliable even if a status can't be fully paged).
     */
    suspend fun getLibrarySnapshot(isAnime: Boolean): Snapshot {
        // [Kitsu.userid] is restored alongside the token, so the same wait applies: reading it too
        // early returns an empty snapshot, which the comparison renders as "nothing is on Kitsu".
        TrackerSessions.await()
        val userId = Kitsu.userid ?: return Snapshot(emptyList(), emptyMap())
        val entries = mutableListOf<LibraryEntry>()
        var counts: Map<String, Int> = emptyMap()
        var offset = 0
        // Smaller pages than a plain library fetch: each row now drags its media + that media's
        // mappings along, so the payload is heavier.
        val limit = 200
        var guard = 0
        // One preferences commit per page instead of the three or four this loop writes per entry.
        // A library of any size wrote thousands of them back to back, and the queued whole-map
        // clones behind each are what ran the heap out here — see [KitsuApi.SeedBatch].
        val seeds = KitsuApi.SeedBatch()
        while (guard++ < 100) {
            val page = tryWithSuspend {
                val url = "${Kitsu.API_URL}/library-entries" +
                    "?filter%5BuserId%5D=$userId" +
                    "&filter%5Bkind%5D=${if (isAnime) "anime" else "manga"}" +
                    "&include=media,media.mappings" +
                    "&page%5Blimit%5D=$limit&page%5Boffset%5D=$offset"
                // cacheTime = 0 for the same reason every other tracker's list read sets it: a
                // comparison has to see the library as it is now, not as the shared client last
                // saw it up to six hours ago.
                json.decodeFromString<LibraryResponse>(
                    ani.dantotsu.client.get(url, readHeaders(), cacheTime = 0).text
                )
            } ?: break
            page.meta?.statusCounts?.let { counts = it }
            // Only the mapping rows. `included` is one flat array holding both the media and their
            // mappings, and the two use separate id sequences that overlap heavily — a live page of
            // 35 entries carries anime 1376/3936/5646 next to mappings 3020/412/1614. Keying the
            // whole array by id alone let a media row land on a mapping's id and win, and the
            // lookup below then found a media where it wanted a mapping, read no `externalSite`
            // from it, and silently dropped that title's AniList/MAL ids.
            val included = page.included.orEmpty().filter { it.type == "mappings" }
                .associateBy { it.id }
            val media = page.included.orEmpty().filter { it.type == "anime" || it.type == "manga" }
                .associateBy { it.id }
            page.data.orEmpty().forEach { e ->
                val mediaId = e.relationships?.media?.data?.id ?: return@forEach
                val m = media[mediaId]
                val attrs = m?.attributes
                var al: Int? = null
                var mal: Int? = null
                m?.relationships?.mappings?.data.orEmpty().forEach { ref ->
                    val refId = ref.id ?: return@forEach
                    val mp = included[refId]?.attributes ?: return@forEach
                    val site = mp.externalSite ?: return@forEach
                    when {
                        site.startsWith("anilist") -> mp.externalId?.toIntOrNull()?.let { al = it }
                        site.startsWith("myanimelist") -> mp.externalId?.toIntOrNull()?.let { mal = it }
                        // MangaUpdates ids are keyed here too — seed so MU→Kitsu resolution is a hit.
                        site.startsWith("mangaupdates") -> mp.externalId?.takeIf { it.isNotBlank() }
                            ?.let { seeds.mediaId("mangaupdates", it, mediaId) }
                    }
                }
                val total = (if (isAnime) attrs?.episodeCount else attrs?.chapterCount) ?: 0
                // Seed the resolution/total caches so the comparison's per-media lookups become
                // cache hits for everything already in the library.
                al?.let { seeds.mediaId(KitsuApi.siteFor("anilist", isAnime), it.toString(), mediaId) }
                mal?.let { seeds.mediaId(KitsuApi.siteFor("myanimelist", isAnime), it.toString(), mediaId) }
                seeds.total(isAnime, mediaId, total)
                entries += LibraryEntry(
                    entryId = e.id,
                    mediaId = mediaId,
                    status = e.attributes?.status,
                    reconsuming = e.attributes?.reconsuming == true,
                    progress = e.attributes?.progress ?: 0,
                    ratingTwenty = e.attributes?.ratingTwenty,
                    startedAt = e.attributes?.startedAt,
                    finishedAt = e.attributes?.finishedAt,
                    title = attrs?.canonicalTitle
                        ?: attrs?.titles?.values?.firstOrNull { !it.isNullOrBlank() },
                    coverUrl = attrs?.posterImage?.small ?: attrs?.posterImage?.medium ?: attrs?.posterImage?.original,
                    total = total,
                    anilistId = al,
                    malId = mal,
                )
            }
            seeds.flush()
            if ((page.data?.size ?: 0) < limit) break
            offset += limit
        }
        seeds.flush()
        // An empty library and a library we were not allowed to read look identical from here —
        // both are a 200 with no rows — and the comparison renders either as "every title is
        // missing from Kitsu". Say which one it was, since the difference is the whole diagnosis.
        if (entries.isEmpty()) {
            Logger.log(
                "Kitsu: empty ${if (isAnime) "anime" else "manga"} library snapshot for user " +
                    "$userId (signed in: ${Kitsu.token != null})"
            )
        }
        return Snapshot(entries, counts)
    }

    // ---- mapping helpers ----

    /**
     * Canonical status (AniList vocabulary) for a Kitsu status + reconsuming flag. `reconsuming`
     * only means REPEATING while the entry is still "current" — a completed entry that kept a stale
     * reconsuming flag reads as COMPLETED, not REPEATING.
     */
    fun toCanon(status: String?, reconsuming: Boolean): String = when {
        reconsuming && status == "current" -> "REPEATING"
        status == "current" -> "CURRENT"
        status == "planned" -> "PLANNING"
        status == "completed" -> "COMPLETED"
        status == "on_hold" || status == "onHold" -> "PAUSED"
        status == "dropped" -> "DROPPED"
        else -> "CURRENT"
    }

    /** Canonical status for one of the `meta.statusCounts` keys. */
    fun countKeyToCanon(key: String): String = when (key) {
        "current" -> "CURRENT"
        "planned" -> "PLANNING"
        "completed" -> "COMPLETED"
        "onHold", "on_hold" -> "PAUSED"
        "dropped" -> "DROPPED"
        else -> "CURRENT"
    }

    fun mapAnilistStatus(status: String?): String? = when (status) {
        "CURRENT", "REPEATING" -> "current"
        "PLANNING" -> "planned"
        "COMPLETED" -> "completed"
        "PAUSED" -> "on_hold"
        "DROPPED" -> "dropped"
        else -> null
    }

    fun mapMangaUpdatesList(listId: Int?): String? = when (listId) {
        0 -> "current"
        1 -> "planned"
        2 -> "completed"
        3 -> "dropped"
        4 -> "on_hold"
        else -> null
    }

    /**
     * AniList POINT_100 (0..100) → Kitsu `ratingTwenty`, kept to the even 2..20 steps Kitsu's
     * classic half-star scale uses. 0 → null (never clears a rating).
     */
    fun toRatingTwenty(score100: Int?): Int? {
        val s = score100?.takeIf { it > 0 } ?: return null
        return (((s + 5) / 10) * 2).coerceIn(2, 20)
    }

    /** Kitsu ratingTwenty back to the 0..100 scale for diff display. */
    fun ratingTwentyTo100(r: Int?): Int? = r?.takeIf { it > 0 }?.times(5)

    /** Rounds a (possibly odd) ratingTwenty from another client to the even step we write. */
    fun normalizeRatingTwenty(r: Int?): Int? = r?.takeIf { it > 0 }?.let { ((it + 1) / 2) * 2 }

    private fun toIsoDate(date: FuzzyDate?): String? {
        val y = date?.year ?: return null
        val m = date.month ?: return null
        val d = date.day ?: return null
        return "%04d-%02d-%02d".format(y, m, d)
    }

    // ---- JSON:API models ----

    @Serializable
    data class LibraryResponse(
        val data: List<LibraryEntryResource>? = null,
        val included: List<MediaResource>? = null,
        val meta: LibraryMeta? = null,
    )

    @Serializable
    data class LibraryMeta(
        @SerialName("statusCounts") val statusCounts: Map<String, Int>? = null,
        val count: Int? = null,
    )

    @Serializable
    data class LibraryEntryResource(
        val id: String,
        val attributes: LibraryEntryAttributes? = null,
        val relationships: LibraryEntryRelationships? = null,
    )

    @Serializable
    data class LibraryEntryAttributes(
        val status: String? = null,
        val progress: Int? = null,
        val ratingTwenty: Int? = null,
        val reconsuming: Boolean? = null,
        val startedAt: String? = null,
        val finishedAt: String? = null,
    )

    @Serializable
    data class LibraryEntryRelationships(val media: KitsuApi.RelationshipRef? = null)

    /** A member of the JSON:API `included` array — either an `anime`/`manga` or a `mappings` row. */
    @Serializable
    data class MediaResource(
        val id: String,
        val type: String? = null,
        val attributes: MediaAttributes? = null,
        val relationships: MediaRelationships? = null,
    )

    @Serializable
    data class MediaRelationships(val mappings: MappingsRel? = null)

    @Serializable
    data class MappingsRel(val data: List<KitsuApi.ResourceRef>? = null)

    @Serializable
    data class MediaAttributes(
        val canonicalTitle: String? = null,
        // Kitsu sends nulls as values here, e.g. {"en":null,"en_jp":"…"}.
        val titles: Map<String, String?>? = null,
        val posterImage: PosterImage? = null,
        val episodeCount: Int? = null,
        val chapterCount: Int? = null,
        // present on `mappings` rows in the same `included` array
        val externalSite: String? = null,
        val externalId: String? = null,
    )

    @Serializable
    data class PosterImage(
        val small: String? = null,
        val medium: String? = null,
        val original: String? = null,
    )

    // ---- write body ----

    @Serializable
    data class EntryBody(val data: EntryData)

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class EntryData(
        val id: String? = null,
        // Mapper.json has encodeDefaults = false, so a plain default would be dropped and Kitsu 400s
        // with "required parameter, type, is missing".
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        val type: String = "libraryEntries",
        val attributes: EntryAttributes,
        val relationships: EntryRelationships? = null,
    )

    @Serializable
    data class EntryAttributes(
        val status: String? = null,
        val progress: Int? = null,
        val ratingTwenty: Int? = null,
        val reconsuming: Boolean? = null,
        val startedAt: String? = null,
        val finishedAt: String? = null,
    )

    @Serializable
    data class EntryRelationships(val user: Rel, val media: Rel)

    @Serializable
    data class Rel(val data: RelData)

    @Serializable
    data class RelData(val type: String, val id: String)
}
