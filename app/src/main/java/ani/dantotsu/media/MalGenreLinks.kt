package ani.dantotsu.media

import android.os.SystemClock
import android.widget.Toast
import ani.dantotsu.R
import ani.dantotsu.getAppString
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.toast

/**
 * MAL's search API only takes free text, which can't find a genre, so a genre chip leads to the
 * genre's page on the website instead. Leaving the app is a lot for one tap on a chip, so the first
 * tap says what a second one will do.
 */
object MalGenreLinks {
    /** Long enough to read the toast and tap again; after that, a tap is a new one. */
    private const val SECOND_TAP_MILLIS = 6_000L

    fun open(genreId: Int, isAnime: Boolean) {
        val path = if (isAnime) "anime" else "manga"
        openLinkInBrowser("https://myanimelist.net/$path/genre/$genreId/")
    }

    /** A tap handler for one chip: explains on the first tap, runs [open] on the second. */
    fun confirmingTap(open: () -> Unit): () -> Unit {
        var explainedAt: Long? = null
        return {
            val now = SystemClock.elapsedRealtime()
            val explained = explainedAt
            if (explained != null && now - explained < SECOND_TAP_MILLIS) {
                explainedAt = null
                open()
            } else {
                explainedAt = now
                toast(getAppString(R.string.mal_genre_tap_again), Toast.LENGTH_LONG)
            }
        }
    }
}
