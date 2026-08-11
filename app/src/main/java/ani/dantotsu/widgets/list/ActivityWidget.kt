package ani.dantotsu.widgets.list

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViewsService
import ani.dantotsu.R
import ani.dantotsu.profile.activity.FeedActivity
import ani.dantotsu.widgets.WidgetData

/**
 * The signed-in account's AniList activity feed — its own posts and list updates mixed with the people
 * it follows, same as [ani.dantotsu.profile.activity.ActivityFragment]'s USER mode. See
 * [ani.dantotsu.widgets.WidgetPrefs.hideOwnActivity] for filtering the signed-in account's own rows out.
 */
class ActivityWidget : MediaListWidget() {
    override val dataset = WidgetData.Dataset.ACTIVITY
    override val service = ActivityRemoteViewsService::class.java
    override val layout = ListLayout.FLAT
    override val titleRes = R.string.widget_activity
    override val emptyRes = R.string.widget_nothing_activity

    override fun headerIntent(context: Context): Intent = Intent(context, FeedActivity::class.java)
}

class ActivityRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = MediaListFactory(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        WidgetData.Dataset.ACTIVITY,
        ListLayout.FLAT,
        intent.getStringExtra(MediaListWidget.EXTRA_PROVIDER)
    )
}
