package ani.dantotsu.widgets.list

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import ani.dantotsu.R
import ani.dantotsu.connections.malsync.LanguageMapper
import ani.dantotsu.profile.activity.ActivityItemBuilder
import ani.dantotsu.util.BitmapUtil
import ani.dantotsu.util.BitmapUtil.downloadImageAsBitmap
import ani.dantotsu.widgets.WidgetData
import ani.dantotsu.widgets.WidgetItem
import ani.dantotsu.widgets.WidgetPrefs
import ani.dantotsu.widgets.WidgetStyle
import ani.dantotsu.widgets.WidgetTime

/** How a list widget arranges its rows. */
enum class ListLayout {
    /** A flat list, soonest or most recent first. */
    FLAT,

    /** Rows grouped under a heading per day, for the week's schedule. */
    BY_DAY
}

/** A rendered line: either a day heading or a media row. */
private sealed interface Row {
    data class Header(val dayMillis: Long) : Row
    data class Media(val item: WidgetItem) : Row
}

/**
 * The rows for every list widget: upcoming episodes, the weekly schedule, and continue watching.
 *
 * One factory rather than three because the only real differences are which dataset is read, whether
 * rows get day headings, and what the second line says.
 */
class MediaListFactory(
    private val context: Context,
    private val appWidgetId: Int,
    private val dataset: WidgetData.Dataset,
    private val layout: ListLayout,
    /** The provider to tell once a rebuild finishes, so it can take its spinner down. */
    private val provider: String? = null
) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<Row> = emptyList()

    override fun onCreate() = refresh()

    /**
     * Called whenever the widget is told its data changed.
     *
     * Runs off the main thread, so it fetches synchronously — [WidgetData] decides whether the network
     * is touched at all. There is deliberately no "already refreshing" flag here: the old factory kept
     * one, set it before fetching, and cleared it only on the success paths, so a single thrown
     * exception left it stuck true and the widget never refreshed again for the life of the process.
     */
    override fun onDataSetChanged() = refresh()

    private fun refresh() {
        WidgetData.loadBlocking(context, dataset)
        val prefs = WidgetPrefs.of(context, appWidgetId)
        val content = prefs.content
        val items = WidgetData.cached(context, dataset)
            .filter { if (it.isAnime) content.includesAnime else content.includesManga }
            // Shared across every Activity widget instance, so "hide my own posts" has to be applied
            // here rather than in WidgetData — each instance can set it differently.
            .filter { dataset != WidgetData.Dataset.ACTIVITY || !(prefs.hideOwnActivity && it.isOwnActivity) }
        val limit = if (prefs.showAllItems) Int.MAX_VALUE else prefs.itemLimit
        rows = when (layout) {
            ListLayout.FLAT -> items.take(limit).map { Row.Media(it) }
            ListLayout.BY_DAY -> groupByDay(items, limit)
        }
        announceFinished()
    }

    /**
     * Tells the provider the rebuild is done.
     *
     * Only a provider can repaint a widget, and this runs in the [RemoteViewsService] — so the spinner
     * a refresh press put up can only be taken down by asking the provider to draw again.
     */
    private fun announceFinished() {
        val provider = provider ?: return
        runCatching {
            context.sendBroadcast(
                Intent(MediaListWidget.ACTION_REFRESHED).apply {
                    component = android.content.ComponentName(context, provider)
                    putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
            )
        }
    }

    /** This week's airings under one heading per day, dropping anything already out or further out than a week. */
    private fun groupByDay(items: List<WidgetItem>, limit: Int): List<Row> {
        val now = System.currentTimeMillis()
        val upcoming = items
            .filter { it.airingAtMillis != null && it.airingAtMillis >= now }
            .filter { WidgetTime.daysBetween(now, it.airingAtMillis!!) <= DAYS_IN_WEEK }
            .sortedBy { it.airingAtMillis }
            .take(limit)
        val rows = mutableListOf<Row>()
        var currentDay = Long.MIN_VALUE
        // Same id on the same day only counts once — mirrors CalendarActivity's own dedup
        // (OtherDetailsViewModel.loadCalendar()), which is also what hides a duplicated page of results
        // whenever the global schedule query pages past its first 50 entries.
        val seenToday = mutableSetOf<Int>()
        for (item in upcoming) {
            val day = WidgetTime.midnight(item.airingAtMillis!!)
            if (day != currentDay) {
                rows.add(Row.Header(day))
                currentDay = day
                seenToday.clear()
            }
            if (!seenToday.add(item.id)) continue
            rows.add(Row.Media(item))
        }
        return rows
    }

    override fun getCount() = rows.size

    override fun getViewTypeCount() = 2

    override fun hasStableIds() = true

    override fun getItemId(position: Int): Long = when (val row = rows.getOrNull(position)) {
        is Row.Header -> row.dayMillis
        is Row.Media -> row.item.id.toLong()
        null -> position.toLong()
    }

    override fun getViewAt(position: Int): RemoteViews {
        val style = WidgetStyle.of(context, appWidgetId)
        return when (val row = rows.getOrNull(position)) {
            is Row.Header -> RemoteViews(context.packageName, R.layout.item_widget_day).apply {
                setTextViewText(R.id.dayLabel, WidgetTime.dayLabel(context, row.dayMillis))
                setTextColor(R.id.dayLabel, style.accent)
            }

            // BY_DAY is the global schedule, which can run to hundreds of entries a week where a
            // personal list rarely exceeds a handful — it gets the compact single-line row rather than
            // the cover-and-two-lines one FLAT uses, so a day's worth actually fits on screen.
            is Row.Media -> if (layout == ListLayout.BY_DAY) calendarRow(row.item, style)
            else mediaRow(row.item, style)

            null -> getLoadingView()
        }
    }

    /** One line: the airing time, then the title and episode. No cover, no source — see [getViewAt]. */
    private fun calendarRow(item: WidgetItem, style: WidgetStyle): RemoteViews =
        RemoteViews(context.packageName, R.layout.item_widget_calendar_row).apply {
            setTextViewText(
                R.id.itemTime,
                item.airingAtMillis?.let { WidgetTime.timeLabel(context, it) }.orEmpty()
            )
            setTextColor(R.id.itemTime, style.accent)
            setTextViewText(
                R.id.itemTitle,
                item.episode?.let { context.getString(R.string.widget_calendar_entry, item.title, it) }
                    ?: item.title
            )
            setTextColor(R.id.itemTitle, style.title)
            setOnClickFillInIntent(R.id.itemRoot, Intent().putExtra("mediaId", item.id))
        }

    private fun mediaRow(item: WidgetItem, style: WidgetStyle): RemoteViews {
        val prefs = WidgetPrefs.of(context, appWidgetId)
        return RemoteViews(context.packageName, R.layout.item_widget_media).apply {
            setTextViewText(R.id.itemTitle, item.title)
            setTextColor(R.id.itemTitle, style.title)
            setTextViewText(R.id.itemSubtitle, subtitle(item))
            setTextColor(R.id.itemSubtitle, style.subtitle)

            // This icon + text slot means two different things depending on the dataset — a waiting
            // row's dub/sub distinction doesn't reduce to a word the way a manga source does, so it
            // gets an icon + short code instead (subtitle() ends with a bare " ·" to lead into it); a
            // recommendation carries no per-source distinction at all, so the same slot instead shows
            // what kind of media it even is, the way the home screen's own recommendation cards do.
            when {
                item.languageId != null -> {
                    setViewVisibility(R.id.itemLanguageIcon, View.VISIBLE)
                    setViewVisibility(R.id.itemLanguageCode, View.VISIBLE)
                    setImageViewResource(
                        R.id.itemLanguageIcon, LanguageMapper.mapLanguage(item.languageId).iconRes
                    )
                    // The vector's own fill is fixed, so without this it wouldn't follow the row's
                    // theme the way the text next to it does.
                    setInt(R.id.itemLanguageIcon, "setColorFilter", style.subtitle)
                    setTextViewText(R.id.itemLanguageCode, LanguageMapper.shortCode(item.languageId))
                    setTextColor(R.id.itemLanguageCode, style.subtitle)
                }

                dataset == WidgetData.Dataset.RECOMMENDATIONS -> {
                    setViewVisibility(R.id.itemLanguageIcon, View.VISIBLE)
                    setViewVisibility(R.id.itemLanguageCode, View.VISIBLE)
                    val (labelRes, iconRes) = when {
                        item.isNovel -> R.string.novel to R.drawable.ic_round_import_contacts_24
                        item.isAnime -> R.string.anime to R.drawable.ic_round_movie_filter_24
                        else -> R.string.manga to R.drawable.ic_round_import_contacts_24
                    }
                    setImageViewResource(R.id.itemLanguageIcon, iconRes)
                    setInt(R.id.itemLanguageIcon, "setColorFilter", style.subtitle)
                    setTextViewText(R.id.itemLanguageCode, context.getString(labelRes))
                    setTextColor(R.id.itemLanguageCode, style.subtitle)
                }

                else -> {
                    setViewVisibility(R.id.itemLanguageIcon, View.GONE)
                    setViewVisibility(R.id.itemLanguageCode, View.GONE)
                }
            }

            if (prefs.showCovers && item.coverUrl.isNotEmpty()) {
                setViewVisibility(R.id.itemCover, View.VISIBLE)
                setImageViewBitmap(R.id.itemCover, downloadImageAsBitmap(item.coverUrl))
            } else {
                setViewVisibility(R.id.itemCover, View.GONE)
            }

            // The cover above is the media's on an activity row; this is the acting user's, sized to the
            // row so it stays recognisable. Tied to showCovers as well — with images off the row is meant
            // to be text only, and this is an image like any other.
            val avatar = item.avatarUrl?.takeIf { prefs.showCovers && it.isNotEmpty() }
                ?.let { downloadImageAsBitmap(it) }
            if (avatar != null) {
                setViewVisibility(R.id.itemAvatar, View.VISIBLE)
                setImageViewBitmap(R.id.itemAvatar, BitmapUtil.toCircularBitmap(avatar))
            } else {
                setViewVisibility(R.id.itemAvatar, View.GONE)
            }

            // Filled into the provider's pending-intent template. MainActivity reads these extras and
            // jumps straight to the next episode/chapter when "continue" is set; a MangaUpdates series
            // has no AniList id to open, so it travels as a series URL instead.
            setOnClickFillInIntent(
                R.id.itemRoot,
                Intent().apply {
                    if (item.muSeriesId != null) {
                        putExtra("muUrl", MU_SERIES_URL + item.muSeriesId.toString(36))
                    } else if (dataset == WidgetData.Dataset.ACTIVITY) {
                        // item.id is the *activity's* id here, not a media id — a text post has no
                        // media at all, so there may be nothing to put.
                        item.mediaId?.let { putExtra("mediaId", it) }
                    } else {
                        putExtra("mediaId", item.id)
                    }
                    putExtra("continue", dataset == WidgetData.Dataset.WAITING)
                }
            )
        }
    }

    /** The second line: when the next episode airs, or how much is waiting to be read or watched. */
    private fun subtitle(item: WidgetItem): String {
        // Neither dataset is "next episode/chapter out" — an activity row already says what happened
        // in its title, and a recommendation carries no progress to report at all.
        if (dataset == WidgetData.Dataset.ACTIVITY) {
            return item.createdAtMillis?.let { ActivityItemBuilder.getDateTime((it / 1000).toInt()) }
                .orEmpty()
        }
        if (dataset == WidgetData.Dataset.RECOMMENDATIONS) {
            // The type icon + label mediaRow() puts in the language slot already says this — see there.
            return ""
        }
        val airing = item.airingAtMillis
        if (dataset == WidgetData.Dataset.AIRING && airing != null) {
            val countdown = WidgetTime.untilAiring(context, airing)
            return item.episode?.let { context.getString(R.string.widget_episode_in, it, countdown) }
                ?: countdown
        }

        // How many are out past the user's progress, and which one to open next. Zero means this row
        // came from the recently-opened fallback rather than from a MALSync count.
        val next = (item.progress ?: 0) + 1
        val behind = item.behind
        val counts = when {
            behind > 0 && item.isAnime -> context.resources.getQuantityString(
                R.plurals.widget_episodes_behind, behind, behind, next
            )

            behind > 0 -> context.resources.getQuantityString(
                R.plurals.widget_chapters_behind, behind, behind, next
            )

            item.isAnime && item.total != null ->
                context.getString(R.string.widget_next_episode_of, next, item.total)

            item.isAnime -> context.getString(R.string.widget_next_episode, next)
            item.total != null -> context.getString(R.string.widget_next_chapter_of, next, item.total)
            else -> context.getString(R.string.widget_next_chapter, next)
        }
        // Where the count came from, appended as its own segment. Manga/MangaUpdates get a plain text
        // name (the site MALSync read, or the scanlation group); anime gets a bare trailing " ·" with
        // nothing after it here — mediaRow() follows it with the icon + code pair instead of a word.
        return when {
            item.languageId != null -> "$counts ·"
            item.source != null -> "$counts · ${item.source}"
            else -> counts
        }
    }

    /** An empty row, shown while a real one is being bound. */
    override fun getLoadingView(): RemoteViews =
        RemoteViews(context.packageName, R.layout.item_widget_media)

    override fun onDestroy() {
        rows = emptyList()
    }

    private companion object {
        const val DAYS_IN_WEEK = 7

        /** MUMediaDetailsActivity already handles these as a VIEW deep link. */
        const val MU_SERIES_URL = "https://www.mangaupdates.com/series/"
    }
}
