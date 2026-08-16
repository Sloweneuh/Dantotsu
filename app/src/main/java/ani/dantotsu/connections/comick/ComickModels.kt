package ani.dantotsu.connections.comick

import java.io.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException
import com.google.gson.annotations.SerializedName
import com.google.gson.JsonElement

data class ComickResponse(
    val comic: ComickComic?,
    val firstChap: ComickFirstChapter?
) : Serializable

data class ComickComic(
    val id: Int?,
    val hid: String?,
    val title: String?,
    val desc: String?,
    val parsed: String?,
    val slug: String?,
    val country: String?,
    val status: Int?,
    val year: Int?,
    val bayesian_rating: String?,
    val rating_count: Int?,
    val follow_rank: Int?,
    val user_follow_count: Int?,
    val last_chapter: Double?,
    val chapter_count: Int?,
    val demographic: Int?,
    val final_chapter: String?,
    val final_volume: String?,
    val has_anime: Boolean?,
    val anime: ComickAnimeInfo?,
    val mu_comics: ComickMuComics?,
    val translation_completed: Boolean?,
    val content_rating: String?,
    val md_titles: List<ComickAlternativeTitle>?,
    val md_comic_md_genres: List<ComickGenre>?,
    val md_covers: List<ComickCover>?,
    val links: ComickLinks?,
    val recommendations: List<ComickRecommendation>?,
    val reviews: List<ComickRawReview>?,
    // --- Anime-only fields. Present only on entries fetched with media_type=anime through
    // /v1.0/comic/{slug}/ (the legacy /comic/{slug}/ path returns 200 for anime but silently
    // omits every one of these), so they default to null and never affect the manga path.
    val media_type: String? = null,
    val anime_profiles: ComickAnimeProfile? = null,
    val trailers: List<ComickTrailer>? = null,
    val anime_companies_to_md_comics: List<ComickCompanyLink>? = null,
    val to_year: Int? = null,
    val rating: String? = null,
) : Serializable {
    /** True when this entry came from the anime catalogue rather than the comic one. */
    val isAnime: Boolean
        get() = media_type == "anime" || anime_profiles != null
}

data class ComickFirstChapter(
    val chap: String?,
    val hid: String?,
    val lang: String?,
    val vol: String?
) : Serializable

data class ComickAnimeInfo(
    val start: String?,
    val end: String?
) : Serializable

/**
 * MAL/Jikan-sourced broadcast and popularity metadata carried by anime entries. [episodes] is the
 * *planned* episode count and is frequently null for currently-airing shows, so it is only ever a
 * hint — the authoritative count for what actually exists is the size of [ComickEpisode] list.
 */
data class ComickAnimeProfile(
    val anime_type: String?,
    val source: String?,
    val episodes: Int?,
    val duration: String?,
    val season: String?,
    val season_year: Int?,
    val broadcast: String?,
    val aired_from: String?,
    val aired_to: String?,
    val mal_rank: Int?,
    val mal_popularity: Int?,
    val mal_score: String?,
    val mal_score_count: Int?,
    val mal_members: Int?,
) : Serializable {

    /**
     * [broadcast] rendered in [zone] instead of JST.
     *
     * Upstream always states the slot in Japan time — "Fridays at 23:00 (JST)" — which is a
     * timezone conversion the reader has to do in their head, and one that often lands on a
     * different day than the one printed. 91 of 100 sampled entries use exactly that shape; the
     * rest are null or "Unknown", and anything that doesn't parse is returned untouched rather
     * than dropped.
     *
     * @return the localized slot, the original string if it can't be parsed, or null if absent
     */
    fun broadcastIn(zone: java.time.ZoneId): ComickBroadcastSlot? {
        val raw = broadcast?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val match = BROADCAST_REGEX.find(raw) ?: return ComickBroadcastSlot(raw)

        val (dayName, hourText, minuteText) = match.destructured
        val day = DAY_NAMES[dayName.lowercase()] ?: return ComickBroadcastSlot(raw)
        val hour = hourText.toIntOrNull()?.takeIf { it in 0..23 }
            ?: return ComickBroadcastSlot(raw)
        val minute = minuteText.toIntOrNull()?.takeIf { it in 0..59 }
            ?: return ComickBroadcastSlot(raw)

        return try {
            // Anchored to the next occurrence rather than an arbitrary date, so the result
            // reflects the DST rules actually in force for the upcoming airing.
            val jst = java.time.ZonedDateTime.now(TOKYO)
                .with(java.time.temporal.TemporalAdjusters.nextOrSame(day))
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            val local = jst.withZoneSameInstant(zone)

            val locale = java.util.Locale.getDefault()
            ComickBroadcastSlot(
                raw = raw,
                day = local.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale),
                time = local.toLocalTime().format(
                    java.time.format.DateTimeFormatter
                        .ofLocalizedTime(java.time.format.FormatStyle.SHORT)
                        .withLocale(locale)
                ),
                zone = zone.getDisplayName(java.time.format.TextStyle.SHORT, locale),
            )
        } catch (_: Exception) {
            ComickBroadcastSlot(raw)
        }
    }

    private companion object {
        val TOKYO: java.time.ZoneId = java.time.ZoneId.of("Asia/Tokyo")

        /** e.g. "Fridays at 23:00 (JST)" — the plural "s" is optional. */
        val BROADCAST_REGEX = Regex(
            """^([A-Za-z]+?)s?\s+at\s+(\d{1,2}):(\d{2})\s*\(JST\)$""",
            RegexOption.IGNORE_CASE
        )

        val DAY_NAMES = mapOf(
            "monday" to java.time.DayOfWeek.MONDAY,
            "tuesday" to java.time.DayOfWeek.TUESDAY,
            "wednesday" to java.time.DayOfWeek.WEDNESDAY,
            "thursday" to java.time.DayOfWeek.THURSDAY,
            "friday" to java.time.DayOfWeek.FRIDAY,
            "saturday" to java.time.DayOfWeek.SATURDAY,
            "sunday" to java.time.DayOfWeek.SUNDAY,
        )
    }
}

/**
 * A broadcast slot ready for display. [day]/[time]/[zone] are set only when the upstream string
 * parsed; otherwise just [raw] is, and callers show it verbatim rather than losing the
 * information.
 */
data class ComickBroadcastSlot(
    val raw: String,
    val day: String? = null,
    val time: String? = null,
    val zone: String? = null,
) : Serializable {
    val isConverted: Boolean get() = day != null && time != null
}

/**
 * The zone to show airing times in: always the device's.
 *
 * Deliberately not `Anilist.timezone`. AniList stores that as a bare UTC offset ("+02:00") with
 * no DST rules attached, so an offset saved in winter reads an hour off for summer airings. The
 * device zone is a real region id and handles the transitions, which matters here because a
 * late-night JST slot already lands on a different weekday in most of the world.
 */
fun broadcastDisplayZone(): java.time.ZoneId = java.time.ZoneId.systemDefault()

data class ComickTrailer(
    val id: Int?,
    val source: String?,
    val youtube_id: String?,
    val url: String?,
    val embed_url: String?,
) : Serializable {
    /**
     * A watchable YouTube URL. Comick usually supplies only [embed_url] in
     * `youtube-nocookie.com/embed/{id}?…` form, which no external player app handles, so the id is
     * lifted out of it and rebuilt as a normal watch link.
     */
    fun watchUrl(): String? {
        youtubeId()?.let { return "https://www.youtube.com/watch?v=$it" }
        url?.takeIf { it.isNotBlank() }?.let { return it }
        return embed_url?.takeIf { it.isNotBlank() }
    }

    /**
     * The bare YouTube video id, which is what an embed needs. Comick usually supplies only
     * [embed_url] in `youtube-nocookie.com/embed/{id}?…` form, so it's lifted back out of that.
     */
    fun youtubeId(): String? {
        youtube_id?.takeIf { it.isNotBlank() }?.let { return it }
        val candidate = embed_url?.takeIf { it.isNotBlank() } ?: url?.takeIf { it.isNotBlank() }
        ?: return null
        return Regex("(?:/embed/|[?&]v=)([A-Za-z0-9_-]{6,})").find(candidate)
            ?.groupValues?.getOrNull(1)
    }
}

data class ComickCompanyLink(
    val role: String?,
    val anime_companies: ComickAnimeCompany?,
) : Serializable

data class ComickAnimeCompany(
    val myid: Int?,
    val name: String?,
    val slug: String?,
    val type: String?,
    val url: String?,
) : Serializable

data class ComickExternalLink(
    val url: String?,
    val name: String?,
    val available: Boolean? = null,
) : Serializable

/** The three MAL-sourced link buckets that only anime entries carry. */
data class ComickMalExternalLinks(
    val resources: List<ComickExternalLink>?,
    val available_at: List<ComickExternalLink>?,
    val streaming_platforms: List<ComickExternalLink>?,
) : Serializable

/**
 * One episode. Episodes are rows in the same table as chapters, distinguished by
 * [entry_type] == "episode" and the presence of [anime_episode_profiles]; [chap] holds the episode
 * number as a string, exactly as chapters hold theirs.
 */
data class ComickEpisode(
    val id: Int?,
    val hid: String?,
    val title: String?,
    val chap: String?,
    val vol: String?,
    val order: Int?,
    val status: String?,
    val entry_type: String?,
    val anime_episode_profiles: ComickEpisodeProfile?,
    val md_chapter_titles: List<ComickChapterTitle>? = null,
    val identities: JsonElement? = null,
) : Serializable {
    /** Episode number, preferring the profile's absolute number over the display string. */
    fun number(): String? =
        anime_episode_profiles?.absolute_episode?.toString()
            ?: chap?.trim()?.takeIf { it.isNotBlank() }

    /** English title if one is tagged as such, else the default title. */
    fun displayTitle(): String? {
        val tagged = md_chapter_titles
            ?.firstOrNull { it.lang?.equals("en", ignoreCase = true) == true }
            ?.title?.takeIf { it.isNotBlank() }
        return tagged ?: title?.takeIf { it.isNotBlank() }
    }

    /**
     * The username of whoever submitted this episode. [identities] arrives as either an object or
     * a single-element array depending on the endpoint, hence the untyped element.
     */
    fun uploader(): String? = try {
        val obj = identities?.let {
            when {
                it.isJsonObject -> it.asJsonObject
                it.isJsonArray -> it.asJsonArray.firstOrNull()?.takeIf { e -> e.isJsonObject }?.asJsonObject
                else -> null
            }
        }
        obj?.getAsJsonObject("traits")?.get("username")?.asString?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** Runtime as a short label, e.g. 1439s -> "24 min". Null when unknown or nonsensical. */
    fun durationLabel(): String? =
        anime_episode_profiles?.duration?.takeIf { it > 0 }?.let { "${(it + 30) / 60} min" }

    /** "special", "recap", "ova"… — anything that isn't a regular numbered episode. */
    fun specialTypeLabel(): String? = anime_episode_profiles?.episode_type
        ?.takeIf { it.isNotBlank() && !it.equals("main", ignoreCase = true) }
        ?.replaceFirstChar { it.uppercase() }
}

data class ComickEpisodeProfile(
    val id: Int? = null,
    val duration: Int?,
    val season_number: Int?,
    val absolute_episode: Int?,
    val synopsis: String?,
    val episode_type: String?,
    val aired_at: String?,
) : Serializable

data class ComickChapterTitle(
    val title: String?,
    val lang: String?,
    val is_default: Boolean? = null,
) : Serializable

/** One row of `/anime/schedule`, i.e. a show plus when it airs during the current week. */
data class ComickScheduleEntry(
    val id: Int?,
    val hid: String?,
    val slug: String?,
    val title: String?,
    val md_titles: List<ComickAlternativeTitle>?,
    val md_covers: List<ComickCover>?,
    val anime_profiles: ComickAnimeProfile?,
    val schedule: ComickScheduleSlot?,
) : Serializable {
    fun displayTitle(): String? = pickEnglishTitle(title, md_titles)
}

data class ComickScheduleSlot(
    val jstWeekday: String?,
    val jstTime: String?,
    val occurrenceAt: String?,
) : Serializable

/**
 * A community-submitted tag from `/comic-tags`. This is the only tag source for anime entries —
 * they carry no `mu_comics`, so the MangaUpdates categories the comic path shows are always empty.
 */
data class ComickUserTag(
    val id: Int?,
    val slug: String?,
    val title: String?,
    val score: Int?,
    val positive_vote: Int?,
    val negative_vote: Int?,
    val comic_count: Int?,
) : Serializable

/** An anime page's server-rendered props: the entry itself plus its episodes, oldest first. */
data class ComickAnimePage(
    val anime: ComickComic?,
    val episodes: List<ComickEpisode>,
) : Serializable

data class ComickMuComics(
    val mu_comic_categories: List<ComickCategory>?
) : Serializable

data class ComickCategory(
    val mu_categories: ComickCategoryInfo?,
    val positive_vote: Int?,
    val negative_vote: Int?
) : Serializable

data class ComickCategoryInfo(
    val title: String?,
    val slug: String?
) : Serializable

data class ComickAlternativeTitle(
    val title: String?,
    val lang: String?,
    /** Marks the entry's primary title for its language — see [pickEnglishTitle]. */
    val is_default: Boolean? = null,
) : Serializable

/** True when [text] contains CJK script — catches entries mistagged as English in md_titles. */
fun hasCJK(text: String) = text.any { c ->
    c.code in 0x3040..0x309F || c.code in 0x30A0..0x30FF ||
        c.code in 0x4E00..0x9FFF || c.code in 0xAC00..0xD7AF || c.code in 0x1100..0x11FF
}

/**
 * The primary display title wherever a Comick title is shown. Shared by every model carrying an
 * `md_titles` array — search results, the media page, recommendations and custom-list entries all
 * use the same shape.
 *
 * Entries routinely carry several English-tagged titles: a romanisation, one or more fan
 * translations, and the one Comick actually shows. Taking the first of them produced titles like
 * "I am the only the one who levels up" and "Wan Piece" for Solo Leveling and One Piece, so the
 * `is_default` flag decides instead — checked against comick.dev's own headings, that agrees with
 * the site on 17 of 18 sampled entries where taking the first agreed on 11.
 *
 * The flag is only ever consulted *within* the English-tagged, non-CJK candidates (some are
 * mistagged): on plenty of entries the default title overall is the Japanese one, and honouring
 * that would defeat the point.
 */
private fun pickEnglishTitle(title: String?, mdTitles: List<ComickAlternativeTitle>?): String? {
    val english = mdTitles
        ?.filter { it.lang?.equals("en", ignoreCase = true) == true }
        ?.mapNotNull { entry ->
            entry.title?.takeIf { it.isNotBlank() && !hasCJK(it) }?.let { entry to it.trim() }
        }
        .orEmpty()

    val preferred = english.firstOrNull { it.first.is_default == true }?.second
        ?: english.firstOrNull()?.second
    return preferred ?: title
}

fun ComickComic.displayTitle(): String? = pickEnglishTitle(title, md_titles)

data class ComickGenre(
    val md_genres: ComickGenreInfo?
) : Serializable

data class ComickGenreInfo(
    val name: String?,
    val type: String?,
    val slug: String?,
    val group: String?
) : Serializable

data class ComickLinks(
    val al: String?,
    val ap: String?,
    val bw: String?,
    val kt: String?,
    val mu: String?,
    val mal: String?,
    val raw: String?,
    val engtl: String?,
    /** Anime entries only: official sites, encyclopedia links and legal streaming platforms. */
    val mal_external_links: ComickMalExternalLinks? = null,
) : Serializable

data class ComickSearchResult(
    val id: Int?,
    val hid: String?,
    val slug: String?,
    val title: String?,
    val country: String?,
    val rating: String?,
    val bayesian_rating: String?,
    val status: Int?,
    val last_chapter: Double?,
    val demographic: Int?,
    val year: Int?
) : Serializable

data class ComickCustomList(
    val id: Int?,
    val title: String?,
    val description: String?,
    val slug: String?,
    val user_id: String?,
    val is_public: Boolean?,
    val visibility: String?,
    val follows_count: Int?,
    val cover: String?,
    val content_rating: String?
) : Serializable

data class ComickListComic(
    val title: String?,
    val slug: String?,
    val hid: String?,
    val last_chapter: Double?,
    val md_titles: List<ComickAlternativeTitle>?,
    val md_covers: List<ComickCover>?,
    val status: Int? = null,
    val country: String? = null,
    val demographic: Int? = null,
    val content_rating: String? = null,
    val bayesian_rating: String? = null,
    val year: Int? = null,
    val uploaded_at: String? = null,
    val genres: List<Int>? = null,
    val translation_completed: Boolean? = null,
    val created_at: String? = null,
) : Serializable {
    fun displayTitle(): String? = pickEnglishTitle(title, md_titles)
}

data class ComickFollowEntry(
    val md_comics: ComickListComic?,
    val created_at: String?,
) : Serializable

data class ComickRecommendation(
    val up: Int?,
    val down: Int?,
    val total: Int?,
    val relates: ComickRecommendedComic?
) : Serializable

data class ComickRecommendedComic(
    val title: String?,
    val slug: String?,
    val hid: String?,
    val md_covers: List<ComickCover>?,
    // Confirmed present on the live comic-details response despite not being read before — same
    // shape as ComickComic.md_titles, so recommendation cards can prefer English too.
    val md_titles: List<ComickAlternativeTitle>? = null,
) : Serializable

data class ComickCover(
    val vol: String?,
    val w: Int?,
    val h: Int?,
    val b2key: String?,
    /** Anime entries carry a MAL CDN poster alongside the Comick-hosted one. */
    val gpurl: String? = null,
    val is_primary: Boolean? = null,
) : Serializable

data class ComickChapter(
    val hid: String?,
    val chap: String?,
    val vol: String?,
    val title: String?,
    val lang: String?,
    val group_name: List<String>?,
    val created_at: String?,
    val updated_at: String?
) : Serializable

/**
 * Present an episode as a chapter so the shared chapter list can render it. Episodes really are
 * chapter rows upstream, so the only losses are the synopsis and the scanlator column, neither of
 * which has an episode meaning.
 */
fun ComickEpisode.toChapter(): ComickChapter = ComickChapter(
    hid = hid,
    chap = number(),
    vol = vol,
    title = displayTitle(),
    lang = null,
    group_name = null,
    created_at = anime_episode_profiles?.aired_at,
    updated_at = null,
)

data class ComickTraits(
    val username: String?,
    val email: String?
) : Serializable

data class ComickIdentity(
    val traits: ComickTraits?
) : Serializable

data class ComickRawReview(
    val id: String?,
    val content: String?,
    val rating: Int?,
    @SerializedName("created_at") val created_at: String?,
    val identities: JsonElement?
) : Serializable

data class ComickReview(
    val id: String?,
    val username: String?,
    val email: String?,
    val content: String?,
    val rating: Int?,
    val createdAt: Int?
) : Serializable

fun ComickRawReview.toComickReview(): ComickReview {
    val unixSeconds = try {
        if (created_at.isNullOrBlank()) null else Instant.parse(created_at).epochSecond.toInt()
    } catch (e: DateTimeParseException) {
        null
    }

    var email: String? = null
    var username: String? = null
    try {
        identities?.let { elem ->
            if (elem.isJsonArray) {
                val arr = elem.asJsonArray
                if (arr.size() > 0 && arr[0].isJsonObject) {
                    val first = arr[0].asJsonObject
                    if (first.has("traits") && first.get("traits").isJsonObject) {
                        val traits = first.getAsJsonObject("traits")
                        if (traits.has("email")) email = traits.get("email").asString
                        if (traits.has("username")) username = traits.get("username").asString
                    }
                }
            } else if (elem.isJsonObject) {
                val obj = elem.asJsonObject
                if (obj.has("traits") && obj.get("traits").isJsonObject) {
                    val traits = obj.getAsJsonObject("traits")
                    if (traits.has("email")) email = traits.get("email").asString
                    if (traits.has("username")) username = traits.get("username").asString
                }
            }
        }
    } catch (_: Exception) {}

    return ComickReview(
        id = id ?: java.util.UUID.randomUUID().toString(),
        username = username,
        email = email,
        content = content,
        rating = rating,
        createdAt = unixSeconds
    )
}

