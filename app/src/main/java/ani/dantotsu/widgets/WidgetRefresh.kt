package ani.dantotsu.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ani.dantotsu.R
import ani.dantotsu.util.Logger
import ani.dantotsu.widgets.list.WaitingWidget
import ani.dantotsu.widgets.list.ScheduleWidget
import ani.dantotsu.widgets.statistics.ProfileStatsWidget
import ani.dantotsu.widgets.upcoming.UpcomingWidget
import java.util.concurrent.TimeUnit

/**
 * Keeps widgets current.
 *
 * `updatePeriodMillis` in the provider XML is the only refresh the widgets used to have, and it is a
 * blunt instrument: the system clamps it to 30 minutes at best, ignores it while dozing, and fires a
 * full `onUpdate` when all that is usually needed is a redraw of countdown text. This drives the data
 * refresh from WorkManager, which can require a network, and separates "fetch again" from "redraw".
 */
object WidgetRefresh {

    private const val WORK_NAME = "widget_refresh"
    private val REFRESH_INTERVAL_MINUTES = 30L

    /**
     * Every widget provider this app publishes, and whether it holds a row list.
     *
     * The stats widget doesn't: telling it its collection changed would name a view its layout has
     * never had.
     */
    private val providers = mapOf<Class<*>, Boolean>(
        UpcomingWidget::class.java to true,
        ScheduleWidget::class.java to true,
        WaitingWidget::class.java to true,
        ProfileStatsWidget::class.java to false
    )

    /** True when at least one widget of any kind is on a home screen. */
    fun anyWidgetExists(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return providers.keys.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    /**
     * Starts the periodic refresh, or stops it once the last widget is gone. Safe to call repeatedly —
     * it replaces the existing schedule rather than stacking another one.
     */
    fun sync(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (!anyWidgetExists(context)) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        )
    }

    /**
     * Redraws every widget and tells the list widgets their data changed.
     *
     * Both halves are needed: `notifyAppWidgetViewDataChanged` re-runs the factory for the rows, while
     * the provider's own `onUpdate` is what repaints the header, the countdown column and the empty
     * state around them.
     */
    fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        for ((provider, hasList) in providers) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isEmpty()) continue
            if (hasList) manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetListView)
            context.sendBroadcast(
                android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    component = ComponentName(context, provider)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
            )
        }
    }

    /**
     * Called when an episode or chapter is opened, which is exactly when progress changes and so when
     * the waiting list stops being true — what was just read is no longer waiting.
     */
    fun onContinueChanged(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, WaitingWidget::class.java))
        if (ids.isEmpty()) return
        WidgetData.invalidate(context, WidgetData.Dataset.WAITING)
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetListView)
    }
}

/** Refreshes each dataset a widget on screen depends on, then repaints them. */
class WidgetRefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!WidgetRefresh.anyWidgetExists(applicationContext)) return Result.success()
        return try {
            for (dataset in WidgetData.Dataset.entries) {
                // CALENDAR is an unpaginated pull of every airing anime worldwide for the week — cheap
                // enough for a screen someone opens occasionally, too heavy to force on every 30-minute
                // tick like the other two (one batch call each). Left to its own 4-hour staleness
                // window instead, by not forcing it here.
                WidgetData.load(applicationContext, dataset, force = dataset != WidgetData.Dataset.CALENDAR)
            }
            WidgetRefresh.renderAll(applicationContext)
            Result.success()
        } catch (e: Throwable) {
            Logger.log("Widget refresh worker failed: $e")
            Logger.log(e)
            // The datasets keep their previous rows, so retrying later is enough.
            Result.retry()
        }
    }
}
