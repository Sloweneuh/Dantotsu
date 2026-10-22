package ani.dantotsu.media.anime

import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * What the Home button (or the recents gesture) does to an episode playing: pause it, shrink it
 * into a picture-in-picture window, or keep its audio going in the background.
 *
 * Stored in PlayerHomeAction. Before it existed, the PiP setting decided this (on: PiP) and,
 * with PiP off, background playback did; an unset value reads them, so nothing changes for anyone
 * until they choose.
 */
object PlayerHomeAction {
    const val PAUSE = 0
    const val PIP = 1
    const val BACKGROUND = 2

    /** Names in value order, for the choice. */
    val names = listOf(R.string.player_home_pause, R.string.picture_in_picture, R.string.player_home_background)

    fun current(): Int {
        val stored = PrefManager.getVal<Int>(PrefName.PlayerHomeAction)
        if (stored in PAUSE..BACKGROUND) return stored
        return when {
            PrefManager.getVal(PrefName.Pip) -> PIP
            PrefManager.getVal(PrefName.PlayerBackgroundPlay) -> BACKGROUND
            else -> PAUSE
        }
    }

    fun set(value: Int) = PrefManager.setVal(PrefName.PlayerHomeAction, value)
}
