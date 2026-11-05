package ani.dantotsu.media

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import ani.dantotsu.R
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangaupdates.MUMediaInfoFragment
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.settings.InfoTabEditorActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** How one line of a details section uses the section's sources. */
@Serializable
data class InfoFieldLine(
    val mode: InfoSourceMode = InfoSourceMode.MERGE,
    /** The sources it uses, in order. Null is all of the section's, in the section's order. */
    val sources: List<String>? = null,
)

/** One section of a custom tab. More than one source means they are merged. */
@Serializable
data class InfoSectionEntry(
    val kind: String,
    val sources: List<String>,
    val hiddenFields: Set<String> = emptySet(),
    /** The details table's lines, in order. Null keeps the usual order. */
    val fieldOrder: List<String>? = null,
    /** Retired for [fieldLines]: the source that alone fills a line. Only still read, from older saves. */
    val fieldSources: Map<String, String> = emptyMap(),
    /** On details, only what a line not in [fieldLines] starts from. */
    val mode: InfoSourceMode = InfoSourceMode.MERGE,
    /** For details with several sources, by line. */
    val fieldLines: Map<String, InfoFieldLine> = emptyMap(),
) {
    /** [fieldLines], with what older saves said about a line in [fieldSources]. */
    val resolvedLines: Map<String, InfoFieldLine>
        get() = fieldSources.mapValues { (_, source) -> InfoFieldLine(mode, listOf(source)) } + fieldLines
}

@Serializable
data class CustomInfoTab(
    val id: String,
    val name: String,
    val icon: String,
    val entries: List<InfoSectionEntry> = emptyList(),
    val visible: Boolean = true,
    val showSources: Boolean = true,
    /** One of [InfoTabLongPress], or a source's key to open that source's page. */
    val longPress: String = InfoTabLongPress.NAME,
)

object InfoTabLongPress {
    const val NAME = "name"
    const val EDIT = "edit"
    const val NONE = "none"
}

/** The source whose page a long press opens, when that is what this tab's long press does. */
val CustomInfoTab.longPressSource: InfoTabType? get() = InfoTabType.forKey(longPress)

/** Long press on a custom tab, for the actions that don't need the host's knowledge of URLs. */
fun Fragment.runCustomTabLongPress(context: InfoTabContext, tab: CustomInfoTab): Boolean {
    when (tab.longPress) {
        InfoTabLongPress.EDIT -> startActivity(
            Intent(requireContext(), InfoTabEditorActivity::class.java)
                .putExtra(InfoTabEditorActivity.EXTRA_CONTEXT, context.name)
                .putExtra(InfoTabEditorActivity.EXTRA_TAB, tab.id)
        )

        InfoTabLongPress.NONE -> Unit
        else -> snackString(tab.name)
    }
    return true
}

/** What the user changed about a source's own tab. Absent means it is drawn as it always was. */
@Serializable
data class BuiltinTabOverride(
    val sectionOrder: List<String>? = null,
    val hiddenSections: Set<String> = emptySet(),
    val hiddenFields: Set<String> = emptySet(),
    val fieldOrder: List<String>? = null,
)

@Serializable
data class InfoContextConfig(
    val tabOrder: List<String>? = null,
    val customTabs: List<CustomInfoTab> = emptyList(),
    val builtins: Map<String, BuiltinTabOverride> = emptyMap(),
)

@Serializable
data class InfoTabsConfig(val contexts: Map<String, InfoContextConfig> = emptyMap())

/**
 * Tab customisation, stored as one JSON string so it backs up and syncs like any other preference.
 * Built-in tab visibility stays in the original per-context preferences, and their order is written
 * there too, so those keep meaning what they always did.
 */
object InfoTabStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun load(): InfoTabsConfig {
        val raw = PrefManager.getVal<String>(PrefName.InfoTabsConfig)
        if (raw.isBlank()) return InfoTabsConfig()
        return try {
            json.decodeFromString<InfoTabsConfig>(raw)
        } catch (e: Exception) {
            Logger.log(e)
            InfoTabsConfig()
        }
    }

    fun get(context: InfoTabContext): InfoContextConfig =
        load().contexts[context.name] ?: InfoContextConfig()

    fun update(context: InfoTabContext, transform: (InfoContextConfig) -> InfoContextConfig) {
        val all = load()
        val current = all.contexts[context.name] ?: InfoContextConfig()
        val updated = all.copy(contexts = all.contexts + (context.name to transform(current)))
        PrefManager.setVal(PrefName.InfoTabsConfig, json.encodeToString(updated))
    }

    fun newTabId(): String = "custom_" + UUID.randomUUID().toString().take(8)
}

/** Icons a tab can wear, grouped for the picker. Stored by key, so a renamed drawable cannot corrupt saved settings. */
object InfoTabIcons {
    class Group(@StringRes val titleRes: Int, val icons: List<Pair<String, Int>>)

    val groups: List<Group> = listOf(
        Group(
            R.string.info_tab_icons_genres,
            listOf(
                "swords" to R.drawable.ic_round_swords_24,
                "castle" to R.drawable.ic_round_castle_24,
                "fort" to R.drawable.ic_round_fort_24,
                "skull" to R.drawable.ic_round_skull_24,
                "local_fire_department" to R.drawable.ic_round_local_fire_department_24,
                "bolt" to R.drawable.ic_round_bolt_24,
                "auto_fix_high" to R.drawable.ic_round_auto_fix_high_24,
                "sports_martial_arts" to R.drawable.ic_round_sports_martial_arts_24,
                "sparkle" to R.drawable.ic_round_auto_awesome_24,
                "rocket_launch" to R.drawable.ic_round_rocket_launch_24,
                "smart_toy" to R.drawable.ic_round_smart_toy_24,
                "planet" to R.drawable.ic_round_planet_24,
                "science" to R.drawable.ic_round_science_outline_24,
                "volunteer_activism" to R.drawable.ic_round_volunteer_activism_24,
                "local_florist" to R.drawable.ic_round_local_florist_24,
                "favorite" to R.drawable.ic_round_favorite_24,
                "heart_broken" to R.drawable.ic_round_heart_broken_24,
                "mood" to R.drawable.ic_round_mood_24,
                "sentiment_very_satisfied" to R.drawable.ic_round_sentiment_very_satisfied_24,
                "face" to R.drawable.ic_round_face_outline_24,
                "bedtime" to R.drawable.ic_round_bedtime_24,
                "school" to R.drawable.ic_round_school_24,
                "ramen_dining" to R.drawable.ic_round_ramen_dining_24,
                "local_cafe" to R.drawable.ic_round_local_cafe_24,
                "pets" to R.drawable.ic_round_pets_24,
                "park" to R.drawable.ic_round_park_24,
                "games" to R.drawable.ic_round_sports_esports_outline_24,
                "shield" to R.drawable.ic_round_shield_outline_24,
                "crown" to R.drawable.ic_round_crown_outline_24,
            )
        ),
        Group(
            R.string.info_tab_icons_media,
            listOf(
                "auto_stories" to R.drawable.ic_round_auto_stories_24,
                "book" to R.drawable.ic_round_book_24,
                "menu_book" to R.drawable.ic_round_menu_book_24,
                "reading" to R.drawable.ic_round_import_contacts_24,
                "library" to R.drawable.ic_round_library_books_24,
                "stories" to R.drawable.ic_round_amp_stories_24,
                "theater_comedy" to R.drawable.ic_round_theater_comedy_24,
                "tv" to R.drawable.ic_round_tv_outline_24,
                "live_tv" to R.drawable.ic_round_live_tv_24,
                "film" to R.drawable.ic_round_movie_24,
                "movie" to R.drawable.ic_round_movie_filter_24,
                "play" to R.drawable.ic_round_play_circle_24,
                "subscriptions" to R.drawable.ic_round_subscriptions_outline_24,
                "mic" to R.drawable.ic_round_mic_24,
                "music" to R.drawable.ic_round_music_note_24,
                "queue_music" to R.drawable.ic_round_queue_music_24,
                "headphones" to R.drawable.ic_round_headphones_24,
                "subtitles" to R.drawable.ic_round_subtitles_24,
                "brush" to R.drawable.ic_round_brush_24,
                "draw" to R.drawable.ic_round_draw_24,
                "palette" to R.drawable.ic_round_palette_outline_24,
                "image" to R.drawable.ic_round_image_24,
            )
        ),
        Group(
            R.string.info_tab_icons_rankings,
            listOf(
                "star" to R.drawable.ic_round_star_24,
                "emoji_events" to R.drawable.ic_round_emoji_events_24,
                "military_tech" to R.drawable.ic_round_military_tech_24,
                "workspace_premium" to R.drawable.ic_round_workspace_premium_24,
                "leaderboard" to R.drawable.ic_round_leaderboard_24,
                "trending_up" to R.drawable.ic_round_trending_up_24,
                "hotel_class" to R.drawable.ic_round_hotel_class_24,
                "insights" to R.drawable.ic_round_insights_24,
                "stats" to R.drawable.ic_round_bar_chart_24,
                "chart" to R.drawable.ic_round_area_chart_24,
                "thumbs" to R.drawable.ic_round_thumb_up_24,
            )
        ),
        Group(
            R.string.info_tab_icons_community,
            listOf(
                "forum" to R.drawable.ic_round_forum_24,
                "comment" to R.drawable.ic_round_forum_outline_24,
                "rate_review" to R.drawable.ic_round_rate_review_24,
                "groups" to R.drawable.ic_round_groups_24,
                "group" to R.drawable.ic_round_group_outline_24,
                "diversity_3" to R.drawable.ic_round_diversity_3_24,
                "person" to R.drawable.ic_round_person_24,
                "campaign" to R.drawable.ic_round_campaign_24,
                "newspaper" to R.drawable.ic_round_newspaper_24,
                "article" to R.drawable.ic_round_article_24,
                "new" to R.drawable.ic_round_new_releases_24,
                "bell" to R.drawable.ic_round_notifications_active_24,
            )
        ),
        Group(
            R.string.info_tab_icons_discovery,
            listOf(
                "travel_explore" to R.drawable.ic_round_travel_explore_24,
                "explore" to R.drawable.ic_round_explore_24,
                "map" to R.drawable.ic_round_map_24,
                "search" to R.drawable.ic_round_search_24,
                "find" to R.drawable.ic_round_manage_search_24,
                "public" to R.drawable.ic_round_public_24,
                "language" to R.drawable.ic_round_language_24,
                "translate" to R.drawable.ic_round_translate_24,
                "link" to R.drawable.ic_round_link_2_24,
                "category" to R.drawable.ic_round_category_24,
                "tag" to R.drawable.ic_round_tag_24,
                "label" to R.drawable.ic_round_label_outline_24,
                "filter" to R.drawable.ic_round_filter_alt_24,
                "idea" to R.drawable.ic_round_lightbulb_outline_24,
            )
        ),
        Group(
            R.string.info_tab_icons_organisation,
            listOf(
                "bookmark" to R.drawable.ic_round_bookmark_24,
                "bookmarks" to R.drawable.ic_round_bookmarks_outline_24,
                "collections" to R.drawable.ic_round_collections_bookmark_24,
                "folder" to R.drawable.ic_round_folder_outline_24,
                "list" to R.drawable.ic_round_view_list_24,
                "grid" to R.drawable.ic_round_grid_view_24,
                "columns" to R.drawable.ic_round_view_column_24,
                "comfy" to R.drawable.ic_round_grid_on_24,
                "widgets" to R.drawable.ic_round_widgets_outline_24,
                "sort" to R.drawable.ic_sort,
                "tune" to R.drawable.ic_round_tune_24,
                "info" to R.drawable.ic_round_info_24,
                "description" to R.drawable.ic_round_description_outline_24,
                "notes" to R.drawable.ic_round_short_text_24,
                "calendar" to R.drawable.ic_round_calendar_today_24,
                "date_range" to R.drawable.ic_round_date_range_24,
                "schedule" to R.drawable.ic_round_schedule_outline_24,
                "history" to R.drawable.ic_round_history_24,
                "home" to R.drawable.ic_round_home_24,
                "eye" to R.drawable.ic_round_visibility_24,
                "shuffle" to R.drawable.ic_round_shuffle_24,
                "compare" to R.drawable.ic_round_compare_arrows_24,
                "source" to R.drawable.ic_source,
                "cloud" to R.drawable.ic_round_cloud_outline_24,
                "devices" to R.drawable.ic_round_devices_outline_24,
                "lock" to R.drawable.ic_round_lock_24,
                "help" to R.drawable.ic_round_help_24,
                "quiz" to R.drawable.ic_round_quiz_outline_24,
                "badge" to R.drawable.ic_round_badge_outline_24,
                "tools" to R.drawable.ic_round_handyman_24,
                "bug" to R.drawable.ic_round_bug_report_outline_24,
            )
        ),
        Group(
            R.string.info_tab_icons_sources,
            listOf(
                "anilist" to R.drawable.ic_anilist,
                "mal" to R.drawable.ic_myanimelist,
                "comick" to R.drawable.ic_comick,
                "mangaupdates" to R.drawable.ic_mangaupdates,
                "mangabaka" to R.drawable.ic_mangabaka,
                "malsync" to R.drawable.ic_malsync,
                "kitsu" to R.drawable.ic_kitsu,
                "simkl" to R.drawable.ic_simkl,
            )
        ),
    )
    private val byKey = groups.flatMap { it.icons }.toMap()
    const val DEFAULT_CUSTOM = "star"

    fun res(key: String?): Int? = key?.let { byKey[it] }
}

/** A tab on a media page's info screen: a source's own, or one the user built. */
sealed class InfoTabEntry {
    abstract val key: String
    abstract val iconRes: Int
    abstract val isVisible: Boolean

    /** Whether it has anything it could show with the connections currently switched on. */
    abstract val isFetching: Boolean

    data class Builtin(
        val type: InfoTabType,
        override val isVisible: Boolean,
    ) : InfoTabEntry() {
        override val key get() = type.key
        override val iconRes get() = type.iconRes
        override val isFetching get() = type.fetchEnabled
    }

    data class Custom(val tab: CustomInfoTab, val context: InfoTabContext) : InfoTabEntry() {
        override val key get() = tab.id
        override val iconRes get() = InfoTabIcons.res(tab.icon) ?: R.drawable.ic_round_star_24
        override val isVisible get() = tab.visible
        override val isFetching
            get() = tab.entries.isEmpty() || tab.entries.any { entry ->
                val kind = InfoSectionKind.forKey(entry.kind)
                kind != null && entry.sources.any { key ->
                    InfoTabType.forKey(key)?.let { context.unavailableReason(it, kind) == null } == true
                }
            }
    }
}

/** The sections of MAL's tab scraped from public pages rather than read from its API. */
private val MAL_PUBLIC_SECTIONS = setOf(InfoSectionKind.STACKS, InfoSectionKind.REVIEWS)

/**
 * Why [source] can't supply [kind] on this context's pages, or null when it can. Whether a login is
 * stored, rather than a live check: this is asked while drawing lists, and an expired session that
 * can't be renewed just leaves a section empty, like a title the source doesn't have.
 */
@StringRes
fun InfoTabContext.unavailableReason(source: InfoTabType, kind: InfoSectionKind): Int? {
    val muPage = this == InfoTabContext.MANGAUPDATES_MANGA || this == InfoTabContext.MANGAUPDATES_NOVEL
    return when {
        !source.fetchEnabled -> R.string.info_tab_fetch_disabled
        source == InfoTabType.MAL && kind !in MAL_PUBLIC_SECTIONS &&
                PrefManager.getNullableVal<MAL.ResponseToken>(PrefName.MALToken, null) == null ->
            R.string.info_tab_login_required

        // MangaUpdates' own pages load without one; its tab on AniList pages does not.
        source == InfoTabType.MANGAUPDATES && !muPage &&
                PrefManager.getVal<String>(PrefName.MangaUpdatesToken).isBlank() ->
            R.string.info_tab_login_required

        else -> null
    }
}

/** What this context is called in the settings. */
fun InfoTabContext.label(context: Context): String = when (this) {
    InfoTabContext.ANILIST_ANIME -> context.getString(R.string.anime)
    InfoTabContext.ANILIST_MANGA -> context.getString(R.string.manga)
    InfoTabContext.ANILIST_NOVEL -> context.getString(R.string.novels)
    InfoTabContext.MANGAUPDATES_MANGA ->
        context.getString(R.string.mangaupdates) + " · " + context.getString(R.string.manga)

    InfoTabContext.MANGAUPDATES_NOVEL ->
        context.getString(R.string.mangaupdates) + " · " + context.getString(R.string.novels)
}

/**
 * The icons standing for this context, the same ones the media page's bottom bar uses. MangaUpdates'
 * own pages are told apart from AniList's by its logo next to them.
 */
val InfoTabContext.iconsRes: List<Int>
    get() = when (this) {
        InfoTabContext.ANILIST_ANIME -> listOf(R.drawable.ic_round_movie_filter_24)
        InfoTabContext.ANILIST_MANGA -> listOf(R.drawable.ic_round_import_contacts_24)
        InfoTabContext.ANILIST_NOVEL -> listOf(R.drawable.ic_round_book_24)
        InfoTabContext.MANGAUPDATES_MANGA ->
            listOf(R.drawable.ic_mangaupdates, R.drawable.ic_round_import_contacts_24)

        InfoTabContext.MANGAUPDATES_NOVEL ->
            listOf(R.drawable.ic_mangaupdates, R.drawable.ic_round_book_24)
    }

/** Every tab of this context in the user's order, hidden ones included. */
fun InfoTabContext.orderedEntries(): List<InfoTabEntry> {
    val config = InfoTabStore.get(this)
    val visibility = savedVisibility()
    val builtins = savedOrder().mapNotNull { index ->
        val type = tabs.getOrNull(index) ?: return@mapNotNull null
        InfoTabEntry.Builtin(type, visibility.getOrNull(index) == true)
    }
    val all: List<InfoTabEntry> = builtins + config.customTabs.map { InfoTabEntry.Custom(it, this) }
    val order = config.tabOrder ?: return all
    val rank = order.withIndex().associate { (i, key) -> key to i }
    // Stable, so anything added since the order was saved keeps its place at the end.
    return all.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
}

fun InfoTabContext.visibleEntries(): List<InfoTabEntry> =
    orderedEntries().filter { it.isVisible && it.isFetching }

/**
 * Everything the tabs on this context's pages are built from: when this changes, a page already
 * open shows the wrong thing. Settings stacked on top of a media page don't end it, so a page
 * compares this on coming back rather than only reading the settings when it opens.
 */
fun InfoTabContext.layoutSignature(): String = listOf(
    InfoTabStore.get(this),
    savedOrder(),
    savedVisibility(),
    tabs.map { unavailableReason(it, InfoSectionKind.DETAILS) },
).toString()

/**
 * The pager behind a media page's info tabs. [rebuild] replaces every page: FragmentStateAdapter
 * only drops a fragment once its id is gone, and its default ids are just the positions, which would
 * leave a tab's old content in place, or under another tab.
 */
class InfoTabsPagerAdapter(
    fragment: Fragment,
    private val count: () -> Int,
    private val create: (Int) -> Fragment,
) : androidx.viewpager2.adapter.FragmentStateAdapter(
    fragment.childFragmentManager, fragment.viewLifecycleOwner.lifecycle
) {
    private var revision = 0L

    override fun getItemCount() = count()
    override fun createFragment(position: Int) = create(position)
    override fun getItemId(position: Int) = revision * MAX_TABS + position
    override fun containsItem(itemId: Long) =
        itemId / MAX_TABS == revision && itemId % MAX_TABS < count()

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun rebuild() {
        revision++
        notifyDataSetChanged()
    }

    private companion object {
        const val MAX_TABS = 1000L
    }
}

/** Saves the order, in both forms: see [InfoTabStore]. */
fun InfoTabContext.saveEntryOrder(keys: List<String>) {
    InfoTabStore.update(this) { it.copy(tabOrder = keys) }
    val builtinOrder = keys.mapNotNull { key -> tabs.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
    val missing = tabs.indices.filterNot { it in builtinOrder }
    PrefManager.setVal(orderPref, builtinOrder + missing)
}

fun InfoTabContext.setBuiltinVisible(type: InfoTabType, visible: Boolean) {
    val index = tabs.indexOf(type).takeIf { it >= 0 } ?: return
    val updated = savedVisibility().toMutableList()
    updated[index] = visible
    PrefManager.setVal(visibilityPref, updated.toList())
}

/**
 * The arrangement of [type]'s own tab: the user's, or the default for a tab they never touched. There
 * is always one, since it's what keeps sections from landing in the order their requests finish.
 */
fun InfoTabContext.sectionRules(type: InfoTabType): InfoSectionRules {
    val defaults = sectionsOf(type)
    val config = InfoTabStore.get(this).builtins[type.key]
        ?: return InfoSectionRules(defaults, emptySet(), emptySet(), null)
    val saved = config.sectionOrder?.mapNotNull { InfoSectionKind.forKey(it) }
        ?.filter { it in defaults }?.distinct()
    // Sections the app gained after this order was saved go back where they normally sit.
    val order = saved?.let { list ->
        val out = list.toMutableList()
        defaults.forEachIndexed { i, kind ->
            if (kind !in out) out.add(i.coerceAtMost(out.size), kind)
        }
        out
    } ?: defaults
    return InfoSectionRules(
        order = order,
        hidden = config.hiddenSections.mapNotNull { InfoSectionKind.forKey(it) }.toSet(),
        hiddenFields = config.hiddenFields,
        fieldOrder = config.fieldOrder,
    )
}

fun InfoTabType.Companion.ofFragment(fragment: Fragment): InfoTabType? = when (fragment) {
    is AniListInfoFragment -> InfoTabType.ANILIST
    is MALInfoFragment -> InfoTabType.MAL
    is ComickInfoFragment -> InfoTabType.COMICK
    is MangaUpdatesInfoFragment, is MUMediaInfoFragment -> InfoTabType.MANGAUPDATES
    is MangaBakaInfoFragment -> InfoTabType.MANGABAKA
    is KitsuInfoFragment -> InfoTabType.KITSU
    is SimklInfoFragment -> InfoTabType.SIMKL
    else -> null
}

/** The fragment drawing [source] on this context's page; MangaUpdates' own page has its own. */
fun InfoTabContext.createSourceFragment(source: InfoTabType): Fragment = when (source) {
    InfoTabType.ANILIST -> AniListInfoFragment()
    InfoTabType.MAL -> MALInfoFragment()
    InfoTabType.COMICK -> ComickInfoFragment()
    InfoTabType.MANGAUPDATES ->
        if (this == InfoTabContext.MANGAUPDATES_MANGA || this == InfoTabContext.MANGAUPDATES_NOVEL)
            MUMediaInfoFragment()
        else MangaUpdatesInfoFragment()

    InfoTabType.MANGABAKA -> MangaBakaInfoFragment()
    InfoTabType.KITSU -> KitsuInfoFragment()
    InfoTabType.SIMKL -> SimklInfoFragment()
}

/**
 * What a standalone page takes from [type]'s info tab: the order of its sections and details lines.
 * Not what the tab hides, since that page is the one place to see a source in full.
 */
fun InfoTabContext.standaloneRules(type: InfoTabType): InfoSectionRules {
    val rules = sectionRules(type)
    val order = rules.order.toMutableList()
    // Simkl's standalone page alone shows AniList's tags; the tab leaves them to the AniList tab.
    if (type == InfoTabType.SIMKL && InfoSectionKind.TAGS !in order) {
        order.add(order.indexOf(InfoSectionKind.GENRES) + 1, InfoSectionKind.TAGS)
    }
    return rules.copy(order = order, hidden = emptySet(), hiddenFields = emptySet())
}

/**
 * Puts [info]'s body in a standalone page's [host] and arranges it as [type]'s tab is. The renderers
 * then draw straight into it, as they do on the tab, which is what makes the order apply.
 */
fun InfoTabContext.standaloneContent(
    info: FragmentMediaInfoBinding, type: InfoTabType, host: ViewGroup,
): InfoSectionContainer {
    val content = info.mediaInfoContainer
    (content.parent as? ViewGroup)?.removeView(content)
    host.addView(content)
    content.applyRules(standaloneRules(type))
    return content
}

fun InfoTabContext.createTabFragment(entry: InfoTabEntry): Fragment = when (entry) {
    is InfoTabEntry.Builtin -> createSourceFragment(entry.type)
    is InfoTabEntry.Custom -> CustomInfoTabFragment.newInstance(this, entry.tab.id)
}

/**
 * Applies the user's arrangement to the source fragments hosted by this manager. From the outside,
 * so the sources need no idea they can be arranged; any already on screen are caught up too.
 */
fun FragmentManager.applyInfoTabRules(context: InfoTabContext) {
    fun apply(fragment: Fragment, view: View?) {
        val type = InfoTabType.ofFragment(fragment) ?: return
        view?.findViewById<InfoSectionContainer>(R.id.mediaInfoContainer)
            ?.applyRules(context.sectionRules(type))
    }
    registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: android.os.Bundle?,
        ) = apply(f, v)
    }, false)
    fragments.forEach { apply(it, it.view) }
}
