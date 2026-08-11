package ani.dantotsu.widgets.statistics

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.util.BitmapUtil.downloadImageAsBitmap
import ani.dantotsu.util.Logger
import ani.dantotsu.widgets.WidgetConfigureActivity
import ani.dantotsu.widgets.WidgetPrefs
import ani.dantotsu.widgets.WidgetRefresh
import ani.dantotsu.widgets.WidgetStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO

/**
 * The user's AniList counts: anime watched, episodes, manga read, chapters.
 *
 * The class stays in this package because the platform stores a widget's provider by
 * [android.content.ComponentName] — moving it would orphan every instance already placed.
 */
class ProfileStatsWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateAppWidget(context, appWidgetManager, it) }
        WidgetRefresh.sync(context)
    }

    /**
     * Redraws after a resize, which is the only way the row count can change — [rowsFor] reads the
     * size the launcher reports, and without this the extra rows would only appear the next time
     * something else happened to refresh the widget.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    /** Handles the refresh button, then defers to [AppWidgetProvider] for the framework's actions. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // One query, and the figures only move every couple of days, so a press refetches.
                updateAppWidget(context, AppWidgetManager.getInstance(context), appWidgetId, force = true)
            }
            return
        }
        super.onReceive(context, intent)
    }

    /** Removes the instance's settings; leaving them behind leaked a prefs file per widget ever added. */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPrefs.delete(context, it) }
        super.onDeleted(context, appWidgetIds)
        WidgetRefresh.sync(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefresh.sync(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefresh.sync(context)
    }

    companion object {

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            force: Boolean = false
        ) {
            val style = WidgetStyle.of(context, appWidgetId)

            // Paint what is already known first, so the widget shows its frame and cached numbers
            // immediately rather than staying blank until the network answers.
            appWidgetManager.updateAppWidget(
                appWidgetId,
                shell(context, appWidgetId, style, refreshing = force).apply {
                    ProfileStatsCache.cached(context)
                        ?.let { applyStats(context, appWidgetId, this, style, it) }
                        ?: applyMessage(context, this, style, R.string.loading)
                }
            )

            launchIO {
                val stats = try {
                    ProfileStatsCache.load(context, force)
                } catch (e: Throwable) {
                    Logger.log("Profile stats widget refresh failed: $e")
                    Logger.log(e)
                    null
                }
                // Avatars are fetched here, on IO. downloadImageAsBitmap() wraps its own runBlocking,
                // so the old code — which called it inside withContext(Dispatchers.Main) — blocked the
                // main thread on a network round trip every time a widget updated.
                val avatar = stats?.avatarUrl
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { downloadImageAsBitmap(it) }.getOrNull() }

                withContext(Dispatchers.Main) {
                    val views = shell(context, appWidgetId, style)  // spinner back to the icon
                    when {
                        stats != null -> applyStats(context, appWidgetId, views, style, stats, avatar)
                        ProfileStatsCache.isLoggedOut() ->
                            applyMessage(context, views, style, R.string.widget_sign_in)
                        // Nothing cached and the fetch failed: say so instead of showing zeroes.
                        else -> applyMessage(context, views, style, R.string.widget_offline)
                    }
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }
        }

        /** The layout with its background, click targets and colours set, but no data yet. */
        private fun shell(
            context: Context,
            appWidgetId: Int,
            style: WidgetStyle,
            refreshing: Boolean = false
        ): RemoteViews =
            RemoteViews(context.packageName, R.layout.statistics_widget).apply {
                style.applyTo(this)
                setViewVisibility(R.id.widgetRefresh, if (refreshing) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.widgetProgress, if (refreshing) View.VISIBLE else View.GONE)
                for (id in LABEL_IDS) setTextColor(id, style.subtitle)
                for (id in VALUE_IDS) setTextColor(id, style.title)
                setTextColor(R.id.userLabel, style.title)
                setTextColor(R.id.widgetMessage, style.subtitle)
                // White vectors, so they need tinting or they disappear on a light background.
                setInt(R.id.widgetRefresh, "setColorFilter", style.subtitle)
                setInt(R.id.widgetConfigure, "setColorFilter", style.subtitle)

                setOnClickPendingIntent(
                    R.id.widgetConfigure,
                    PendingIntent.getActivity(
                        context,
                        appWidgetId,
                        WidgetConfigureActivity.intent(
                            context, appWidgetId, ProfileStatsWidget::class.java
                        ),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                setOnClickPendingIntent(
                    R.id.widgetRefresh,
                    PendingIntent.getBroadcast(
                        context,
                        appWidgetId + REQUEST_REFRESH_OFFSET,
                        Intent(context, ProfileStatsWidget::class.java).apply {
                            action = ACTION_REFRESH
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            // PendingIntents are matched by filterEquals(), which ignores extras.
                            data = Uri.parse("dantotsu://widget/refresh/$appWidgetId")
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

        private fun applyStats(
            context: Context,
            appWidgetId: Int,
            views: RemoteViews,
            style: WidgetStyle,
            stats: ProfileStats,
            avatar: Bitmap? = null
        ) = views.apply {
            setViewVisibility(R.id.widgetMessage, View.GONE)
            setViewVisibility(R.id.statsContainer, View.VISIBLE)

            setTextViewText(R.id.userLabel, context.getString(R.string.user_stats, stats.userName))
            avatar?.let { setImageViewBitmap(R.id.userAvatar, it) }

            val prefs = WidgetPrefs.of(context, appWidgetId)
            val slots = prefs.statSlots
            val rows = rowsFor(context, appWidgetId)
            STAT_ROW_IDS.forEachIndexed { index, row ->
                val visible = index < rows
                row.container?.let { setViewVisibility(it, if (visible) View.VISIBLE else View.GONE) }
                if (!visible) return@forEachIndexed
                bindStat(context, slots[index * 2], stats, row.leftValue, row.leftLabel)
                bindStat(context, slots[index * 2 + 1], stats, row.rightValue, row.rightLabel)
            }

            setOnClickPendingIntent(
                R.id.widgetContainer,
                PendingIntent.getActivity(
                    context,
                    stats.userId,
                    Intent(context, ProfileActivity::class.java)
                        .putExtra("userId", stats.userId)
                        // Same tab index the home screen's own stats row opens straight to — this
                        // widget has nothing else on it worth landing on Overview for.
                        .putExtra("selectedTab", 2)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        /** The four view ids one row of the grid is built from. */
        private class StatRow(
            val leftValue: Int,
            val leftLabel: Int,
            val rightValue: Int,
            val rightLabel: Int,
            /** Null for the first two rows, which are always on screen and so never toggled. */
            val container: Int? = null
        )

        private val STAT_ROW_IDS = listOf(
            StatRow(R.id.topLeftItem, R.id.topLeftLabel, R.id.topRightItem, R.id.topRightLabel),
            StatRow(
                R.id.bottomLeftItem, R.id.bottomLeftLabel,
                R.id.bottomRightItem, R.id.bottomRightLabel
            ),
            StatRow(
                R.id.rowThreeLeftItem, R.id.rowThreeLeftLabel,
                R.id.rowThreeRightItem, R.id.rowThreeRightLabel, R.id.statsRowThree
            ),
            StatRow(
                R.id.rowFourLeftItem, R.id.rowFourLeftLabel,
                R.id.rowFourRightItem, R.id.rowFourRightLabel, R.id.statsRowFour
            )
        )

        /**
         * How many of the four stat rows this instance is currently tall enough for.
         *
         * The widget's minimum is two cells ([MIN_HEIGHT_DP]) and a home screen cell is roughly
         * [CELL_HEIGHT_DP], so every cell the user drags it taller buys one more row. Read from the
         * options bundle rather than measured anywhere, because a RemoteViews tree can't measure itself
         * — the launcher tells us the size it gave us and that is all there is to go on.
         */
        fun rowsFor(context: Context, appWidgetId: Int): Int {
            val options = AppWidgetManager.getInstance(context)?.getAppWidgetOptions(appWidgetId)
            val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            // A brand new widget has no options yet; two rows is what it shipped with before this
            // was responsive at all, so that's the safe assumption rather than showing everything.
            if (heightDp <= 0) return DEFAULT_STAT_ROWS
            val extra = (heightDp - MIN_HEIGHT_DP) / CELL_HEIGHT_DP
            return (DEFAULT_STAT_ROWS + extra).coerceIn(DEFAULT_STAT_ROWS, STAT_ROW_IDS.size)
        }

        /** Matches minHeight in the provider XML: two cells. */
        private const val MIN_HEIGHT_DP = 110
        private const val CELL_HEIGHT_DP = 70
        const val DEFAULT_STAT_ROWS = 2

        /**
         * One grid cell. [ProfileStat.NONE] leaves both value and label [View.INVISIBLE] rather than
         * [View.GONE] — a row built from two fixed cells has nowhere for the other to reflow into, so
         * hiding one only leaves it blank rather than collapsing the layout around it.
         */
        private fun RemoteViews.bindStat(
            context: Context,
            stat: ProfileStat,
            stats: ProfileStats,
            valueId: Int,
            labelId: Int
        ) {
            val visibility = if (stat == ProfileStat.NONE) View.INVISIBLE else View.VISIBLE
            setViewVisibility(valueId, visibility)
            setViewVisibility(labelId, visibility)
            if (stat == ProfileStat.NONE) return
            setTextViewText(valueId, stat.value(stats))
            setTextViewText(labelId, context.getString(stat.labelRes))
        }

        /**
         * One centred line, for every state that isn't "here are your stats".
         *
         * This used to be spelled out across the four stat slots — "please" / "log in" / "or join" /
         * "anilist", one word per corner — which read as gibberish in any language whose word order
         * differs and could not say anything else, such as that the request had simply failed.
         */
        private fun applyMessage(
            context: Context,
            views: RemoteViews,
            style: WidgetStyle,
            messageRes: Int
        ) = views.apply {
            setViewVisibility(R.id.statsContainer, View.GONE)
            setViewVisibility(R.id.widgetMessage, View.VISIBLE)
            setTextViewText(R.id.widgetMessage, context.getString(messageRes))
            setTextColor(R.id.widgetMessage, style.subtitle)
            setOnClickPendingIntent(
                R.id.widgetContainer,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        private const val ACTION_REFRESH = "ani.dantotsu.widgets.action.REFRESH"
        private const val REQUEST_REFRESH_OFFSET = 200_000

        // Derived from the row table rather than listed again: these drive the theme colours, and a
        // hand-written copy is exactly how rows three and four ended up drawing in the layout's
        // default grey while the two above them followed the widget's palette.
        private val LABEL_IDS =
            STAT_ROW_IDS.flatMap { listOf(it.leftLabel, it.rightLabel) }.toIntArray()
        private val VALUE_IDS =
            STAT_ROW_IDS.flatMap { listOf(it.leftValue, it.rightValue) }.toIntArray()
    }
}
