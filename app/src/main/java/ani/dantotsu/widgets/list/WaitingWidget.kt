package ani.dantotsu.widgets.list

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViewsService
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.home.HomeFragment
import ani.dantotsu.widgets.WidgetData

/**
 * Everything with episodes or chapters out that the user hasn't reached — newest release first.
 *
 * Built on what is actually available rather than on the AniList list: chapter counts come from the
 * unread cache MALSync feeds (MangaUpdates series included), episode counts from MALSync's own
 * per-anime progress. That distinction is the whole point for a large list — AniList reports no
 * chapter count at all for most releasing manga, so it cannot say which of a thousand entries has
 * something new. See [WidgetData] for the sources and the recently-opened fallback.
 */
class WaitingWidget : MediaListWidget() {
    override val dataset = WidgetData.Dataset.WAITING
    override val service = WaitingRemoteViewsService::class.java
    override val layout = ListLayout.FLAT
    override val titleRes = R.string.widget_waiting
    override val emptyRes = R.string.widget_nothing_waiting

    /**
     * Refresh rebuilds the manga/MangaUpdates half from the cache rather than refetching.
     *
     * A real refresh there would mean asking MangaUpdates about every entry on the user's lists —
     * dozens of requests for one button press. What the button is for on that half is picking up what
     * already changed locally: chapters read since the last check
     * ([ani.dantotsu.widgets.WidgetProgress]) and whatever the scheduled unread check last wrote.
     */
    override val refetchOnRefresh = false

    /**
     * The anime half is the exception: one batch call, not one request per series, so it refetches on
     * every press — the same thing a homepage redraw already does with no staleness cache of its own.
     */
    override val refreshAnimeOnRefresh = true

    // Mixes anime and manga, so neither list tab is uniquely "the" relevant one — Home is where the
    // unread-chapters/continue sections this widget mirrors actually live.
    override fun headerIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra("FRAGMENT_CLASS_NAME", HomeFragment::class.java.name)
}

class WaitingRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = MediaListFactory(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        WidgetData.Dataset.WAITING,
        ListLayout.FLAT,
        intent.getStringExtra(MediaListWidget.EXTRA_PROVIDER)
    )
}
