package ani.dantotsu.widgets.upcoming

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViewsService
import ani.dantotsu.widgets.WidgetData
import ani.dantotsu.widgets.list.ListLayout
import ani.dantotsu.widgets.list.MediaListFactory
import ani.dantotsu.widgets.list.MediaListWidget

class UpcomingRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = MediaListFactory(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        WidgetData.Dataset.AIRING,
        ListLayout.FLAT,
        intent.getStringExtra(MediaListWidget.EXTRA_PROVIDER)
    )
}
