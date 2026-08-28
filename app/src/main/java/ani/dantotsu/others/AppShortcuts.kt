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

    /** (Re)publishes the fixed four. Cheap and idempotent — safe to call on every process start. */
    fun publish(context: Context) {
        // Best-effort: a failure here (ShortcutManager rate limit, a wiped user) must not take the
        // app's startup down with it.
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    fixed(context, "search", R.string.search, R.drawable.sc_search, 0, ACTION_SEARCH),
                    fixed(context, "downloads", R.string.downloads, R.drawable.sc_downloads, 1, ACTION_DOWNLOADS),
                    fixed(context, "incognito", R.string.incognito_mode, R.drawable.sc_incognito, 2, ACTION_INCOGNITO),
                    fixed(context, "offline", R.string.offline_mode, R.drawable.sc_offline, 3, ACTION_OFFLINE),
                ),
            )
        }.onFailure { Logger.log(it) }
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
