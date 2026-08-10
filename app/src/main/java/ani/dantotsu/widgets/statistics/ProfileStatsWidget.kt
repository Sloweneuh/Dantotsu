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
            bindStat(context, prefs.statSlot1, stats, R.id.topLeftItem, R.id.topLeftLabel)
            bindStat(context, prefs.statSlot2, stats, R.id.topRightItem, R.id.topRightLabel)
            bindStat(context, prefs.statSlot3, stats, R.id.bottomLeftItem, R.id.bottomLeftLabel)
            bindStat(context, prefs.statSlot4, stats, R.id.bottomRightItem, R.id.bottomRightLabel)

            setOnClickPendingIntent(
                R.id.widgetContainer,
                PendingIntent.getActivity(
                    context,
                    stats.userId,
                    Intent(context, ProfileActivity::class.java)
                        .putExtra("userId", stats.userId)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        /**
         * One grid cell. [ProfileStat.NONE] leaves both value and label [View.INVISIBLE] rather than
         * [View.GONE] — a 2x2 grid built from four fixed cells has nowhere for the others to reflow
         * into, so hiding one only leaves it blank rather than collapsing the layout around it.
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

        private val LABEL_IDS = intArrayOf(
            R.id.topLeftLabel, R.id.topRightLabel, R.id.bottomLeftLabel, R.id.bottomRightLabel
        )
        private val VALUE_IDS = intArrayOf(
            R.id.topLeftItem, R.id.topRightItem, R.id.bottomLeftItem, R.id.bottomRightItem
        )
    }
}
