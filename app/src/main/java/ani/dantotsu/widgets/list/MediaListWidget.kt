package ani.dantotsu.widgets.list

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.annotation.StringRes
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.widgets.WidgetConfigureActivity
import ani.dantotsu.widgets.WidgetData
import ani.dantotsu.widgets.WidgetPrefs
import ani.dantotsu.widgets.WidgetRefresh
import ani.dantotsu.widgets.WidgetStatus
import ani.dantotsu.widgets.WidgetStyle

/**
 * Shared behaviour for the widgets that show a list of media.
 *
 * Subclasses only declare what differs: which dataset they read, which service feeds their rows, and
 * what their heading says.
 */
abstract class MediaListWidget : AppWidgetProvider() {

    protected abstract val dataset: WidgetData.Dataset
    protected abstract val service: Class<out RemoteViewsService>
    protected abstract val layout: ListLayout

    @get:StringRes
    protected abstract val titleRes: Int

    /** Shown in place of the list when there is nothing to show. */
    @get:StringRes
    protected abstract val emptyRes: Int

    /**
     * Whether the refresh button re-fetches, or only rebuilds from what is already cached.
     *
     * The airing widgets cost one AniList query, so refresh means refetch. The waiting widget would
     * have to ask MangaUpdates about every entry on the list — far too much for a button press — so it
     * rebuilds from the cache the unread check keeps up to date, which is also where a chapter read
     * since the last refresh gets picked up from. See [refreshAnimeOnRefresh] for the one part of that
     * widget which does refetch on a press.
     */
    protected open val refetchOnRefresh: Boolean = true

    /**
     * Whether the refresh button always pulls the anime half of the waiting dataset fresh, independent
     * of [refetchOnRefresh].
     *
     * Anime progress is one MALSync batch call, the same one the home screen's row makes on every
     * redraw with no staleness cache of its own — cheap enough that a press should behave the same way
     * here, even though the manga/MangaUpdates half next to it stays cache-only.
     */
    protected open val refreshAnimeOnRefresh: Boolean = false

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
        WidgetRefresh.sync(context)
    }

    /**
     * Handles the refresh button, then defers to [AppWidgetProvider] for the framework's own actions.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        when (intent.action) {
            ACTION_REFRESH -> {
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val manager = AppWidgetManager.getInstance(context)
                    if (refetchOnRefresh) WidgetData.invalidate(context, dataset)
                    if (refreshAnimeOnRefresh) WidgetData.requestAnimeRefresh(context)
                    // Spinner first, so the press is acknowledged before any work starts; the factory
                    // rebuilds the rows and reports back with ACTION_REFRESHED to put the icon back.
                    render(context, manager, appWidgetId, refreshing = true)
                    manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetListView)
                }
                return
            }

            ACTION_REFRESHED -> {
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    render(context, AppWidgetManager.getInstance(context), appWidgetId)
                }
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        render(context, appWidgetManager, appWidgetId)
    }

    /**
     * Drops each removed instance's own settings.
     *
     * The upcoming widget used to call `clear()` on a file every instance shared, so removing one
     * widget reset the colours of the ones left behind.
     */
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

    fun render(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        refreshing: Boolean = false
    ) {
        val style = WidgetStyle.of(context, appWidgetId)
        val views = RemoteViews(context.packageName, R.layout.widget_media_list).apply {
            style.applyTo(this)
            setViewVisibility(R.id.widgetRefresh, if (refreshing) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.widgetProgress, if (refreshing) View.VISIBLE else View.GONE)
            setTextViewText(R.id.widgetTitle, context.getString(titleRes))
            setTextColor(R.id.widgetTitle, style.title)
            // The header icons are white vectors, which vanish on a light background — Material You in
            // light mode, or any light app theme. Tint them like the text they sit beside.
            setInt(R.id.widgetRefresh, "setColorFilter", style.subtitle)
            setInt(R.id.widgetConfigure, "setColorFilter", style.subtitle)

            setTextViewText(R.id.widgetEmpty, context.getString(emptyMessage(context)))
            setTextColor(R.id.widgetEmpty, style.subtitle)
            setEmptyView(R.id.widgetListView, R.id.widgetEmpty)

            // The data intent has to differ per widget id, or the platform hands every instance the
            // same cached factory — filterEquals() ignores extras, hence the id in the data URI.
            setRemoteAdapter(
                R.id.widgetListView,
                Intent(context, service).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    // So the factory can report back here when it has finished rebuilding: only a
                    // provider can repaint a widget, and the factory has no other way to name one.
                    putExtra(EXTRA_PROVIDER, this@MediaListWidget.javaClass.name)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
            )

            setPendingIntentTemplate(R.id.widgetListView, itemTemplate(context))
            setOnClickPendingIntent(R.id.widgetRefresh, refreshIntent(context, appWidgetId))
            setOnClickPendingIntent(R.id.widgetConfigure, configureIntent(context, appWidgetId))
            setOnClickPendingIntent(R.id.widgetHeader, openApp(context, appWidgetId))
        }
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    /** Distinguishes "nothing scheduled" from "not signed in", "offline" and "still loading". */
    @StringRes
    private fun emptyMessage(context: Context): Int = when (WidgetData.status(context, dataset)) {
        WidgetStatus.LOGGED_OUT -> R.string.widget_sign_in
        WidgetStatus.OFFLINE -> R.string.widget_offline
        WidgetStatus.ERROR -> R.string.widget_error
        WidgetStatus.LOADING -> R.string.loading
        WidgetStatus.OK, WidgetStatus.EMPTY -> emptyRes
    }

    /** Receives each row's fill-in intent; see [MediaListFactory.mediaRow]. */
    private fun itemTemplate(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_ITEM,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("fromWidget", true)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /** Comes back to this provider's [onReceive]; the data URI is what keeps the instances apart. */
    private fun refreshIntent(context: Context, appWidgetId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            appWidgetId + REQUEST_REFRESH_OFFSET,
            Intent(context, javaClass).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse("dantotsu://widget/refresh/$appWidgetId")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun configureIntent(context: Context, appWidgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            appWidgetId,
            WidgetConfigureActivity.intent(context, appWidgetId, javaClass),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openApp(context: Context, appWidgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            appWidgetId + REQUEST_OPEN_OFFSET,
            Intent(context, MainActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val ACTION_REFRESH = "ani.dantotsu.widgets.action.REFRESH"

        /** Sent by [MediaListFactory] once the rows are rebuilt, to take the spinner back down. */
        const val ACTION_REFRESHED = "ani.dantotsu.widgets.action.REFRESHED"
        const val EXTRA_PROVIDER = "provider"

        private const val REQUEST_ITEM = 0
        private const val REQUEST_OPEN_OFFSET = 100_000
        private const val REQUEST_REFRESH_OFFSET = 200_000
    }
}
