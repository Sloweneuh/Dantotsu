package ani.dantotsu.widgets.upcoming

import ani.dantotsu.R
import ani.dantotsu.widgets.WidgetData
import ani.dantotsu.widgets.list.ListLayout
import ani.dantotsu.widgets.list.MediaListWidget

/**
 * The next episodes of the anime on the user's list, soonest first.
 *
 * Everything but the wiring lives in [MediaListWidget]. The class stays in this package because the
 * platform stores a widget's provider by [android.content.ComponentName]: moving or renaming it would
 * orphan every instance already on a home screen.
 */
class UpcomingWidget : MediaListWidget() {
    override val dataset = WidgetData.Dataset.AIRING
    override val service = UpcomingRemoteViewsService::class.java
    override val layout = ListLayout.FLAT
    override val titleRes = R.string.upcoming
    override val emptyRes = R.string.no_shows_to_display
}
