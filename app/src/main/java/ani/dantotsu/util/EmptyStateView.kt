package ani.dantotsu.util

import androidx.core.view.isVisible
import ani.dantotsu.R
import ani.dantotsu.databinding.LayoutSearchEmptyStateBinding
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Shown when a search/browse genuinely came back with zero matches - distinct from [showError]
 * so a bad query and a bad connection never look the same on screen. Pass [message] to override
 * the generic copy with something more specific to the caller (e.g. a source-broke-not-just-empty
 * hint); otherwise falls back to the generic "no results" text.
 */
fun LayoutSearchEmptyStateBinding.showNoResults(message: CharSequence? = null) {
    root.isVisible = true
    emptyStateIcon.setImageResource(R.drawable.ic_round_search_off_24)
    if (message != null) emptyStateText.text = message
    else emptyStateText.setText(R.string.search_no_results)
}

/**
 * Shown when the request itself failed (network error, timeout, bad response, etc). Pass
 * [message] for a more specific error (e.g. a timeout, or text with a clickable span); otherwise
 * falls back to the generic "couldn't fetch results" text.
 */
fun LayoutSearchEmptyStateBinding.showError(message: CharSequence? = null) {
    root.isVisible = true
    emptyStateIcon.setImageResource(R.drawable.ic_round_error_outline_24)
    if (message != null) emptyStateText.text = message
    else emptyStateText.setText(R.string.search_fetch_error)
}

fun LayoutSearchEmptyStateBinding.hideEmptyState() {
    root.isVisible = false
}

/**
 * Appends an already-classified [reason] (see [friendlyErrorReason]) below the generic
 * "couldn't fetch results" copy - e.g. "No internet connection". Falls back to the plain generic
 * message when [reason] is null.
 */
fun LayoutSearchEmptyStateBinding.showErrorWithReason(reason: String?) {
    val base = root.context.getString(R.string.search_fetch_error)
    showError(if (reason != null) "$base\n$reason" else base)
}

/** Convenience for when the caller has the [Throwable] itself rather than a pre-extracted reason. */
fun LayoutSearchEmptyStateBinding.showError(cause: Throwable?) {
    showErrorWithReason(friendlyErrorReason(cause))
}

/**
 * Turns a caught [Throwable] into a short, human-readable reason to show alongside a generic
 * error placeholder. Common low-level network failures get a canned, friendly phrase; anything
 * else falls back to the exception's own message - but only when it's short enough to read like a
 * sentence rather than a raw type/stack dump, since this codebase's own thrown exceptions (rate
 * limits, expired tokens, etc.) already carry user-facing text.
 */
fun friendlyErrorReason(cause: Throwable?): String? {
    if (cause == null) return null
    return when (cause) {
        is UnknownHostException -> "No internet connection"
        is SocketTimeoutException -> "Request timed out"
        is ConnectException -> "Couldn't reach the server"
        is SSLException -> "Secure connection failed"
        else -> friendlyErrorReason(cause.message)
    }
}

/** Same filtering as the [Throwable] overload, for callers that only have a bare message string. */
fun friendlyErrorReason(message: String?): String? =
    message?.trim()?.takeIf { it.isNotBlank() && it.length <= 120 }
