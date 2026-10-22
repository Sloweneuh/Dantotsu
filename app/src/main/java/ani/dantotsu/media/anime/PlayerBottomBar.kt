package ani.dantotsu.media.anime

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.media3.ui.PlayerView
import ani.dantotsu.R
import com.google.android.material.color.MaterialColors

/**
 * The player's bottom row of buttons, fitted to the width it has. Held upright a phone has room
 * for about eight of them and the player has a dozen, so the ones that don't fit — least needed
 * first — move behind a "more" button at the end, as a menu that presses them.
 *
 * A button left out is given no width rather than hidden: whether a button belongs in the row at
 * all (subtitles only with subtitles, casting only when turned on…) is decided elsewhere through
 * its visibility, and that has to keep meaning what it says.
 */
class PlayerBottomBar(playerView: PlayerView) {

    private class Item(val view: View, val label: Int, val fallbackIcon: Int) {
        /** The width the layout gave it, to go back to. */
        val width = view.layoutParams.width
    }

    private val bar: LinearLayout = playerView.findViewById(R.id.exo_bottom_buttons)
    private val more: ImageButton = playerView.findViewById(R.id.exo_more)
    private val buttonWidth = (48 * bar.resources.displayMetrics.density).toInt()

    /** Most needed first: these are the last to move behind "more". */
    private val items = listOf(
        item(R.id.exo_source, R.string.source, R.drawable.ic_round_source_24),
        item(R.id.exo_sub, R.string.subtitles, R.drawable.ic_round_subtitles_24),
        item(R.id.exo_audio, R.string.audio_tracks, R.drawable.ic_round_audiotrack_24),
        item(R.id.exo_screen, R.string.player_resize, R.drawable.ic_round_fullscreen_24),
        item(R.id.exo_settings, R.string.settings, R.drawable.ic_round_video_settings_24),
        item(R.id.exo_pip, R.string.picture_in_picture, R.drawable.ic_round_picture_in_picture_alt_24),
        item(R.id.exo_cast, R.string.player_cast, R.drawable.ic_round_cast_24),
        item(R.id.exo_skip_op_ed, R.string.auto_skip, R.drawable.ic_round_play_disabled_24),
        item(R.id.exo_rotate, R.string.player_rotate, R.drawable.ic_round_screen_rotation_alt_24),
        item(R.id.exo_clip, R.string.clip, R.drawable.ic_round_movie_edit_24),
        item(R.id.exo_screenshot, R.string.screenshot, R.drawable.ic_round_screenshot_frame_2_24),
        item(R.id.exo_handoff, R.string.continue_on_another_device, R.drawable.ic_round_cast_24),
    )

    private var overflow: List<Item> = emptyList()

    private var menuShowing = false
    private var menuClosedAt = 0L

    /**
     * Whether a change of window focus is this bar's own menu opening or closing — not the user
     * leaving — so "pause when not in focus" should leave playback alone. Closing counts for a
     * moment after, as focus comes back just behind the dismissal.
     */
    val ownsFocusChange: Boolean
        get() = menuShowing || SystemClock.elapsedRealtime() - menuClosedAt < 500

    private fun item(id: Int, label: Int, icon: Int) = Item(bar.findViewById(id), label, icon)

    init {
        // Any button shown or hidden, or a rotation, lays the row out again; fitted after that
        // layout rather than during it.
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bar.post(::fit) }
        more.setOnClickListener { showMenu() }
    }

    private fun fit() {
        val available = bar.width - bar.paddingStart - bar.paddingEnd
        if (available <= 0) return
        val wanted = items.filter { it.view.visibility == View.VISIBLE }
        val fitsAll = wanted.size * buttonWidth <= available
        // Room for the "more" button itself once anything has to go behind it.
        val shown = if (fitsAll) wanted.toSet()
        else wanted.take(((available - buttonWidth) / buttonWidth).coerceAtLeast(0)).toSet()

        val hidden = wanted.filter { it !in shown }
        wanted.forEach { setWidth(it, if (it in shown) it.width else 0) }
        if (more.visibility != (if (hidden.isEmpty()) View.GONE else View.VISIBLE)) {
            more.visibility = if (hidden.isEmpty()) View.GONE else View.VISIBLE
        }
        // Kept in the row's own order, so the menu reads the way the row does.
        overflow = items.filter { it in hidden }.sortedBy { bar.indexOfChild(it.view) }
    }

    private fun setWidth(item: Item, width: Int) {
        val params = item.view.layoutParams
        if (params.width == width) return
        params.width = width
        item.view.layoutParams = params
    }

    private fun showMenu() {
        val context = more.context
        val tint = MaterialColors.getColor(more, com.google.android.material.R.attr.colorOnSurface)
        PopupMenu(context, more).apply {
            overflow.forEachIndexed { i, item ->
                menu.add(0, i, i, item.label).icon =
                    ((item.view as? ImageView)?.drawable?.constantState?.newDrawable()
                        ?: ContextCompat.getDrawable(context, item.fallbackIcon))
                        ?.mutate()?.apply { setTint(tint) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setForceShowIcon(true)
            setOnDismissListener {
                menuShowing = false
                menuClosedAt = SystemClock.elapsedRealtime()
            }
            setOnMenuItemClickListener { menuItem ->
                overflow.getOrNull(menuItem.itemId)?.view?.performClick()
                true
            }
            menuShowing = true
            show()
        }
    }
}
