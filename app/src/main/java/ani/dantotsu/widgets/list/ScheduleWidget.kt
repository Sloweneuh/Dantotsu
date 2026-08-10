package ani.dantotsu.widgets.list

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViewsService
import ani.dantotsu.R
import ani.dantotsu.widgets.WidgetData

/**
 * The week's airing schedule, under a heading per day — the same global schedule
 * [ani.dantotsu.media.CalendarActivity] shows in the app, not filtered to the user's own list.
 *
 * Reads [WidgetData.Dataset.CALENDAR] rather than the personalised [WidgetData.Dataset.AIRING] the
 * Upcoming widget uses — a deliberately different dataset, since the two mean different things ("what
 * I'm about to watch" vs. "what's airing at all") and can't share a cache. See [MediaListFactory] for
 * the compact per-row layout this needs: a global schedule can run to hundreds of entries a week where
 * a personal one rarely exceeds a handful.
 */
class ScheduleWidget : MediaListWidget() {
    override val dataset = WidgetData.Dataset.CALENDAR
    override val service = ScheduleRemoteViewsService::class.java
    override val layout = ListLayout.BY_DAY
    override val titleRes = R.string.widget_this_week
    override val emptyRes = R.string.widget_nothing_this_week
}

class ScheduleRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = MediaListFactory(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        WidgetData.Dataset.CALENDAR,
        ListLayout.BY_DAY,
        intent.getStringExtra(MediaListWidget.EXTRA_PROVIDER)
    )
}
