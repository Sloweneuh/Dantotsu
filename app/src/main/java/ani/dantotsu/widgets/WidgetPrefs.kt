package ani.dantotsu.widgets

import android.content.Context
import android.graphics.Color
import ani.dantotsu.widgets.statistics.ProfileStat

/** How a widget instance picks its colours. */
enum class WidgetThemeMode {
    /** The system's Material You palette on Android 12+, the app theme below that. */
    MATERIAL_YOU,

    /** The Dantotsu theme picked in settings. */
    APP_THEME,

    /** The colours stored on this widget instance. */
    CUSTOM;

    companion object {
        fun from(name: String?): WidgetThemeMode =
            entries.firstOrNull { it.name == name } ?: MATERIAL_YOU
    }
}

/** Which list a media-list widget draws from. */
enum class WidgetContent { ANIME, MANGA, BOTH;

    val includesAnime get() = this != MANGA
    val includesManga get() = this != ANIME

    companion object {
        fun from(name: String?): WidgetContent = entries.firstOrNull { it.name == name } ?: BOTH
    }
}

/**
 * Settings for one widget instance.
 *
 * Keyed by widget id and nothing else: ids are unique across providers, so every widget type shares
 * this one shape while every *instance* gets its own file. The upcoming widget used to keep its
 * settings in a single file shared by all of its instances, which is why they could never be
 * configured apart — and why deleting any one of them wiped the rest ([UpcomingWidget] cleared the
 * whole file per deleted id). [migrate] carries those old values across once.
 */
class WidgetPrefs private constructor(context: Context, private val appWidgetId: Int) {

    private val prefs = context.getSharedPreferences(fileName(appWidgetId), Context.MODE_PRIVATE)

    var themeMode: WidgetThemeMode
        get() = WidgetThemeMode.from(prefs.getString(THEME_MODE, null))
        set(value) = prefs.edit().putString(THEME_MODE, value.name).apply()

    /** Custom-mode colours. Only read when [themeMode] is [WidgetThemeMode.CUSTOM]. */
    var backgroundColor: Int
        get() = prefs.getInt(BACKGROUND_COLOR, DEFAULT_BACKGROUND)
        set(value) = prefs.edit().putInt(BACKGROUND_COLOR, value).apply()

    /**
     * Whether the background fades out towards the bottom.
     *
     * Replaces the old pair of "top background"/"bottom background" colours. Two arbitrary endpoint
     * colours are what forced a gradient bitmap to be rendered at each widget's measured size; one
     * colour plus this flag is the same look in practice — the second colour was transparent in every
     * default — and it draws from a plain tinted drawable that resizes on its own.
     */
    var fadeBackground: Boolean
        get() = prefs.getBoolean(FADE_BACKGROUND, false)
        set(value) = prefs.edit().putBoolean(FADE_BACKGROUND, value).apply()

    var titleColor: Int
        get() = prefs.getInt(TITLE_COLOR, Color.WHITE)
        set(value) = prefs.edit().putInt(TITLE_COLOR, value).apply()

    var subtitleColor: Int
        get() = prefs.getInt(SUBTITLE_COLOR, Color.WHITE)
        set(value) = prefs.edit().putInt(SUBTITLE_COLOR, value).apply()

    /** How many rows a list widget shows at most. Meaningless while [showAllItems] is set. */
    var itemLimit: Int
        get() = prefs.getInt(ITEM_LIMIT, DEFAULT_ITEM_LIMIT)
        set(value) = prefs.edit().putInt(ITEM_LIMIT, value.coerceIn(1, MAX_ITEM_LIMIT)).apply()

    /**
     * Skip [itemLimit] and show every row the dataset has.
     *
     * The global schedule widget is the case this exists for: a week's worth of every airing anime
     * runs to dozens of rows a day, where 25 total cuts it off partway through the *first* day. A
     * RemoteViews `ListView` backed by `setRemoteAdapter` binds rows lazily through its own service
     * connection rather than shipping them all in one Parcel, so there is no platform-side reason to
     * cap the count the way the old fixed slider implied there was.
     */
    var showAllItems: Boolean
        get() = prefs.getBoolean(SHOW_ALL_ITEMS, false)
        set(value) = prefs.edit().putBoolean(SHOW_ALL_ITEMS, value).apply()

    var content: WidgetContent
        get() = WidgetContent.from(prefs.getString(CONTENT, null))
        set(value) = prefs.edit().putString(CONTENT, value.name).apply()

    /** Whether covers are drawn. Off is both faster and cheaper on a widget that only lists titles. */
    var showCovers: Boolean
        get() = prefs.getBoolean(SHOW_COVERS, true)
        set(value) = prefs.edit().putBoolean(SHOW_COVERS, value).apply()

    /** Filters the signed-in account's own posts out of the Activity widget. */
    var hideOwnActivity: Boolean
        get() = prefs.getBoolean(HIDE_OWN_ACTIVITY, false)
        set(value) = prefs.edit().putBoolean(HIDE_OWN_ACTIVITY, value).apply()

    /**
     * What the profile stats widget's four cells show — top-left, top-right, bottom-left, bottom-right.
     * Defaults match the grid before this was configurable at all, so an existing widget's look doesn't
     * change until the user actually opens its settings.
     */
    var statSlot1: ProfileStat
        get() = ProfileStat.from(prefs.getString(STAT_SLOT_1, null), ProfileStat.ANIME_COUNT)
        set(value) = prefs.edit().putString(STAT_SLOT_1, value.name).apply()

    var statSlot2: ProfileStat
        get() = ProfileStat.from(prefs.getString(STAT_SLOT_2, null), ProfileStat.EPISODES_WATCHED)
        set(value) = prefs.edit().putString(STAT_SLOT_2, value.name).apply()

    var statSlot3: ProfileStat
        get() = ProfileStat.from(prefs.getString(STAT_SLOT_3, null), ProfileStat.MANGA_COUNT)
        set(value) = prefs.edit().putString(STAT_SLOT_3, value.name).apply()

    var statSlot4: ProfileStat
        get() = ProfileStat.from(prefs.getString(STAT_SLOT_4, null), ProfileStat.CHAPTERS_READ)
        set(value) = prefs.edit().putString(STAT_SLOT_4, value.name).apply()

    /**
     * Rows three and four, shown only once the widget is resized tall enough to hold them — see
     * [ani.dantotsu.widgets.statistics.ProfileStatsWidget]. Their defaults are the stats the first two
     * rows leave out, so growing the widget shows something new rather than repeating what's above.
     */
    var statSlot5: ProfileStat
        get() = ProfileStat.from(prefs.getString(STAT_SLOT_5, null), ProfileStat.DAYS_WATCHED)
        set(value) = prefs.edit().putString(STAT_SLOT_5, value.name).apply()

    var statSlot6: ProfileStat
        get() = ProfileStat.from(prefs.getString(STAT_SLOT_6, null), ProfileStat.ANIME_MEAN_SCORE)
        set(value) = prefs.edit().putString(STAT_SLOT_6, value.name).apply()

    var statSlot7: ProfileStat
        get() = ProfileStat.from(prefs.getString(STAT_SLOT_7, null), ProfileStat.VOLUMES_READ)
        set(value) = prefs.edit().putString(STAT_SLOT_7, value.name).apply()

    var statSlot8: ProfileStat
        get() = ProfileStat.from(prefs.getString(STAT_SLOT_8, null), ProfileStat.MANGA_MEAN_SCORE)
        set(value) = prefs.edit().putString(STAT_SLOT_8, value.name).apply()

    /** Every stat slot in grid order, so callers can index rows without naming each one. */
    val statSlots: List<ProfileStat>
        get() = listOf(
            statSlot1, statSlot2, statSlot3, statSlot4,
            statSlot5, statSlot6, statSlot7, statSlot8
        )

    /**
     * Pulls the settings this instance would have had before per-instance storage existed, once.
     *
     * Only colours the user actually chose are carried over — an untouched legacy file holds the same
     * translucent black defaults everyone got, and treating those as a deliberate choice would pin
     * every existing widget to [WidgetThemeMode.CUSTOM] and hide the new theming for good.
     */
    fun migrate(context: Context) {
        if (prefs.getInt(VERSION, 0) >= CURRENT_VERSION) return
        val legacyFiles = listOf(
            LEGACY_UPCOMING_PREFS,
            "$LEGACY_STATS_PREFS$appWidgetId"
        )
        for (name in legacyFiles) {
            val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (!legacy.contains(BACKGROUND_COLOR) && !legacy.contains(TITLE_COLOR)) continue
            themeMode = WidgetThemeMode.CUSTOM
            backgroundColor = legacy.getInt(BACKGROUND_COLOR, DEFAULT_BACKGROUND)
            // The old second colour only ever read as "fade to nothing" or "don't fade": a mostly
            // transparent bottom colour was the gradient, an opaque one was a flat background.
            fadeBackground = Color.alpha(legacy.getInt(LEGACY_BACKGROUND_FADE, 0)) < 0x40
            titleColor = legacy.getInt(TITLE_COLOR, Color.WHITE)
            // The two widgets named their second text colour differently.
            subtitleColor = legacy.getInt(
                LEGACY_COUNTDOWN_COLOR,
                legacy.getInt(LEGACY_STATS_COLOR, Color.WHITE)
            )
            break
        }
        prefs.edit().putInt(VERSION, CURRENT_VERSION).apply()
    }

    /** Copies every setting onto another instance, used when a widget is duplicated. */
    fun copyTo(other: WidgetPrefs) {
        other.themeMode = themeMode
        other.backgroundColor = backgroundColor
        other.fadeBackground = fadeBackground
        other.titleColor = titleColor
        other.subtitleColor = subtitleColor
        other.itemLimit = itemLimit
        other.showAllItems = showAllItems
        other.content = content
        other.showCovers = showCovers
        other.hideOwnActivity = hideOwnActivity
        other.statSlot1 = statSlot1
        other.statSlot2 = statSlot2
        other.statSlot3 = statSlot3
        other.statSlot4 = statSlot4
        other.statSlot5 = statSlot5
        other.statSlot6 = statSlot6
        other.statSlot7 = statSlot7
        other.statSlot8 = statSlot8
    }

    companion object {
        fun of(context: Context, appWidgetId: Int): WidgetPrefs =
            WidgetPrefs(context, appWidgetId).apply { migrate(context) }

        /** Drops an instance's settings file. Called from `onDeleted`, so removed widgets leave nothing. */
        fun delete(context: Context, appWidgetId: Int) {
            context.getSharedPreferences(fileName(appWidgetId), Context.MODE_PRIVATE)
                .edit().clear().apply()
            context.getSharedPreferences("$LEGACY_STATS_PREFS$appWidgetId", Context.MODE_PRIVATE)
                .edit().clear().apply()
        }

        private fun fileName(appWidgetId: Int) = "ani.dantotsu.widget.$appWidgetId"

        /** The translucent black both widgets shipped with, kept as the custom-mode starting point. */
        const val DEFAULT_BACKGROUND = 0x80000000.toInt()
        const val DEFAULT_ITEM_LIMIT = 10
        const val MAX_ITEM_LIMIT = 25

        private const val VERSION = "version"
        private const val CURRENT_VERSION = 1

        private const val THEME_MODE = "theme_mode"
        private const val BACKGROUND_COLOR = "background_color"
        private const val FADE_BACKGROUND = "fade_background"
        private const val TITLE_COLOR = "title_text_color"
        private const val SUBTITLE_COLOR = "subtitle_text_color"
        private const val ITEM_LIMIT = "item_limit"
        private const val SHOW_ALL_ITEMS = "show_all_items"
        private const val CONTENT = "content"
        private const val SHOW_COVERS = "show_covers"
        private const val HIDE_OWN_ACTIVITY = "hide_own_activity"
        private const val STAT_SLOT_1 = "stat_slot_1"
        private const val STAT_SLOT_2 = "stat_slot_2"
        private const val STAT_SLOT_3 = "stat_slot_3"
        private const val STAT_SLOT_4 = "stat_slot_4"
        private const val STAT_SLOT_5 = "stat_slot_5"
        private const val STAT_SLOT_6 = "stat_slot_6"
        private const val STAT_SLOT_7 = "stat_slot_7"
        private const val STAT_SLOT_8 = "stat_slot_8"

        private const val LEGACY_UPCOMING_PREFS = "ani.dantotsu.widgets.UpcomingWidget"
        private const val LEGACY_STATS_PREFS = "ani.dantotsu.widgets.Statistics."
        private const val LEGACY_BACKGROUND_FADE = "background_fade"
        private const val LEGACY_COUNTDOWN_COLOR = "countdown_text_color"
        private const val LEGACY_STATS_COLOR = "stats_text_color"
    }
}
