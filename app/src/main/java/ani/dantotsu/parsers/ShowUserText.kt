package ani.dantotsu.parsers

import android.widget.TextView

/**
 * Routes this parser's "Searching : x" / "Found : x" updates to [target], on the main thread.
 *
 * [BaseParser.setUserText] is called from whatever thread the search happens to run on, so the hop
 * is required. It goes through [android.view.View.post] rather than a coroutine because the
 * spelling this replaces — `MainScope().launch { ... }` — built a fresh, never-cancelled scope on
 * every single update, and posting to the view is all the update actually needs.
 *
 * The listener holds [target] strongly and the parser holding it is cached for the life of the
 * process, so whoever installs one is responsible for dropping it again when the view goes away:
 * see [BaseSources.flushTextListeners].
 */
fun BaseParser.showUserTextOn(target: TextView) {
    showUserTextListener = { text -> target.post { target.text = text } }
}
