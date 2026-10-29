package ani.dantotsu.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.media.history.WatchHistory
import ani.dantotsu.util.AppScope
import ani.dantotsu.util.Logger
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import kotlinx.coroutines.launch

/**
 * One tap back into what was last watched or read: the newest [WatchHistory] entry, with its cover
 * and the episode or chapter it was left on. Opens that media's page over the home screen, as the
 * launcher's Continue shortcut does.
 *
 * Built from History alone, so it needs no account and no network beyond the cover; redrawn by
 * [WatchHistory] whenever its newest entry may have changed.
 */
class ContinueWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        AppScope.launch {
            try {
                render(context.applicationContext, manager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPrefs.delete(context, it) }
    }

    companion object {

        /** Redraws every Continue widget there is; nothing when none is placed. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, ContinueWidget::class.java))
            if (ids.isEmpty()) return
            AppScope.launch { render(app, manager, ids) }
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val entry = WatchHistory.entries(limit = 1).firstOrNull()
            val cover = entry?.cover?.let { coverBitmap(context, it) }
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_continue)
                val style = WidgetStyle.of(context, id)
                style.applyTo(views)
                views.setTextColor(R.id.continueLabel, style.accent)
                views.setTextColor(R.id.continueTitle, style.title)
                views.setTextColor(R.id.continueLine, style.subtitle)
                views.setTextColor(R.id.continueEmpty, style.subtitle)
                if (entry == null) {
                    views.setViewVisibility(R.id.continueContent, View.GONE)
                    views.setViewVisibility(R.id.continueEmpty, View.VISIBLE)
                    views.setOnClickPendingIntent(R.id.widgetContainer, openApp(context))
                } else {
                    views.setViewVisibility(R.id.continueContent, View.VISIBLE)
                    views.setViewVisibility(R.id.continueEmpty, View.GONE)
                    views.setTextViewText(R.id.continueTitle, entry.title)
                    views.setTextViewText(R.id.continueLine, entry.itemLine(context))
                    if (cover != null) views.setImageViewBitmap(R.id.continueCover, cover)
                    else views.setImageViewResource(R.id.continueCover, R.drawable.ic_round_play_arrow_24)
                    views.setOnClickPendingIntent(R.id.widgetContainer, openEntry(context, entry))
                }
                manager.updateAppWidget(id, views)
            }
        }

        /** The cover at its drawn size, corners rounded; null when it can't be fetched. Blocking. */
        private fun coverBitmap(context: Context, url: String): Bitmap? = runCatching {
            val density = context.resources.displayMetrics.density
            Glide.with(context).asBitmap().load(url)
                .transform(CenterCrop(), RoundedCorners((10 * density).toInt()))
                .submit((52 * density).toInt(), (74 * density).toInt())
                .get()
        }.onFailure { Logger.log("ContinueWidget: cover failed: ${it.message}") }.getOrNull()

        /** The media page over the home screen, so Back lands in the app. */
        private fun openEntry(context: Context, entry: WatchHistory.Entry): PendingIntent {
            val home = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            return PendingIntent.getActivities(
                context, REQUEST_ENTRY, arrayOf(home, entry.openIntent(context)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, REQUEST_APP, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private const val REQUEST_ENTRY = 0x434F4E54 // "CONT"
        private const val REQUEST_APP = REQUEST_ENTRY + 1
    }
}
