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
 * The same on-list-and-planned recommendations the home screen's row shows —
 * [ani.dantotsu.connections.anilist.AnilistQueries.getRecommendations] is the exact function it calls.
 */
class RecommendationsWidget : MediaListWidget() {
    override val dataset = WidgetData.Dataset.RECOMMENDATIONS
    override val service = RecommendationsRemoteViewsService::class.java
    override val layout = ListLayout.FLAT
    override val titleRes = R.string.widget_recommendations
    override val emptyRes = R.string.widget_nothing_recommendations

    // The recommendations row lives on the home screen, and there's nowhere else in the app that shows
    // this exact list — the "see more" screen only works reached from a running HomeFragment, which
    // built the full Media objects it needs; a cold tap from the widget has only the lightweight rows
    // WidgetItem caches, not enough to render that screen properly.
    override fun headerIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra("FRAGMENT_CLASS_NAME", HomeFragment::class.java.name)
}

class RecommendationsRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = MediaListFactory(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        WidgetData.Dataset.RECOMMENDATIONS,
        ListLayout.FLAT,
        intent.getStringExtra(MediaListWidget.EXTRA_PROVIDER)
    )
}
