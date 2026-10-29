package ani.dantotsu.others

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.IconCompat
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.media.SearchActivity
import ani.dantotsu.media.history.WatchHistory
import ani.dantotsu.util.AppScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import ani.dantotsu.toast
import ani.dantotsu.util.Logger

/**
 * The app's launcher shortcuts: the actions on a long press of the icon, and the per-search-type
 * icons a user can drag onto the home screen from the search sheet.
 *
 * Everything is published at runtime rather than from `res/xml/shortcuts.xml`, because a static
 * shortcut's `<intent>` needs a literal `targetPackage` and the alpha/debug builds carry a `.beta`
 * [applicationId] suffix a literal can't follow. Built here from `Intent(context, …::class)` the
 * package is always right. The only cost is that the fixed entries appear after the first launch
 * rather than straight after install.
 */
object AppShortcuts {

    /** Put on the shortcut's Intent; [MainActivity] reads it to pick which action to run. */
    const val EXTRA_ACTION = "shortcut_action"
    const val ACTION_SEARCH = "search"
    const val ACTION_DOWNLOADS = "downloads"
    const val ACTION_INCOGNITO = "incognito"
    const val ACTION_OFFLINE = "offline"

    private fun launcherIntent(context: Context, action: String) =
        Intent(context, MainActivity::class.java).apply {
            this.action = Intent.ACTION_VIEW
            // Re-run MainActivity.onCreate so the action is handled even when the app is already
            // open. Deliberately not SINGLE_TOP, which would divert to onNewIntent and skip it.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_ACTION, action)
        }

    private fun fixed(
        context: Context,
        id: String,
        @StringRes label: Int,
        @DrawableRes icon: Int,
        rank: Int,
        action: String,
    ) = ShortcutInfoCompat.Builder(context, id)
        .setShortLabel(context.getString(label))
        .setLongLabel(context.getString(label))
        .setIcon(IconCompat.createWithResource(context, icon))
        .setRank(rank)
        .setIntent(launcherIntent(context, action))
        .build()

    /**
     * (Re)publishes the fixed four, then the "Continue" one above them. Cheap and idempotent — safe
     * to call on every process start. Off the main thread: the continue entry reads the history
     * database and may fetch a cover.
     */
    fun publish(context: Context) {
        val app = context.applicationContext
        AppScope.launch {
            // Best-effort: a failure here (ShortcutManager rate limit, a wiped user) must not take
            // the app's startup down with it.
            runCatching {
                ShortcutManagerCompat.setDynamicShortcuts(
                    app,
                    listOf(
                        fixed(app, "search", R.string.search, R.drawable.sc_search, 1, ACTION_SEARCH),
                        fixed(app, "downloads", R.string.downloads, R.drawable.sc_downloads, 2, ACTION_DOWNLOADS),
                        fixed(app, "incognito", R.string.incognito_mode, R.drawable.sc_incognito, 3, ACTION_INCOGNITO),
                        fixed(app, "offline", R.string.offline_mode, R.drawable.sc_offline, 4, ACTION_OFFLINE),
                    ),
                )
            }.onFailure { Logger.log(it) }
            // setDynamicShortcuts just removed it.
            continueKey = null
            refreshContinue(app)
        }
    }

    private const val CONTINUE_ID = "continue"

    /** The media the continue shortcut points at, so a new episode of the same one isn't a republish. */
    @Volatile
    private var continueKey: String? = null

    /**
     * Points the "Continue <title>" shortcut at the newest [WatchHistory] entry, or removes it when
     * history is empty. It opens the media page over the home screen, so Back lands in the app.
     * Ranked first, so on a launcher that shows four it is the fixed "Offline" that drops off.
     * Blocking; call off the main thread.
     */
    fun refreshContinue(context: Context) {
        runCatching {
            val entry = WatchHistory.entries(limit = 1).firstOrNull()
            if (entry == null) {
                ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(CONTINUE_ID))
                continueKey = null
                return
            }
            val key = "${entry.mediaId}:${entry.type}:${entry.muSeriesId}"
            if (key == continueKey) return
            val home = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val details = entry.openIntent(context).apply {
                if (action == null) action = Intent.ACTION_VIEW
            }
            val info = ShortcutInfoCompat.Builder(context, CONTINUE_ID)
                .setShortLabel(entry.title)
                .setLongLabel(context.getString(R.string.shortcut_continue, entry.title))
                .setIcon(coverIcon(context, entry.cover) ?: discIcon(context, R.drawable.ic_round_play_arrow_24))
                .setRank(0)
                .setIntents(arrayOf(home, details))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, info)
            continueKey = key
        }.onFailure { Logger.log(it) }
    }

    /** The cover, centre-cropped to a square, or null when it can't be fetched. Blocking. */
    private fun coverIcon(context: Context, url: String?): IconCompat? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val bitmap = Glide.with(context).asBitmap().load(url).centerCrop().submit(192, 192).get()
            IconCompat.createWithAdaptiveBitmap(bitmap)
        }.getOrNull()
    }

    /**
     * Asks the launcher to drop a standalone "Search <type>" icon on the home screen — invoked from
     * a long press on a tile in the search sheet. The launcher shows its own confirmation.
     */
    fun pinSearch(context: Context, typeKey: String, typeLabel: String, @DrawableRes glyph: Int) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            toast(R.string.shortcut_pin_unsupported)
            return
        }
        val title = context.getString(R.string.shortcut_search_type, typeLabel)
        val intent = Intent(context, SearchActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", typeKey)
            putExtra("search", true)
        }
        val info = ShortcutInfoCompat.Builder(context, "search_${typeKey.lowercase()}")
            .setShortLabel(title)
            .setLongLabel(title)
            .setIcon(discIcon(context, glyph))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /**
     * The in-app `ic_*` glyphs are theme-tinted and go invisible on a launcher surface, so compose
     * the same white-disc-and-dark-glyph look the fixed shortcuts' drawables have.
     */
    private fun discIcon(context: Context, @DrawableRes glyph: Int): IconCompat {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE) // full-bleed; the launcher masks it to its own shape
        ContextCompat.getDrawable(context, glyph)?.mutate()?.let { drawable ->
            DrawableCompat.setTint(drawable, 0xFF3C4043.toInt())
            val pad = (size * 0.28f).toInt()
            drawable.setBounds(pad, pad, size - pad, size - pad)
            drawable.draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}
