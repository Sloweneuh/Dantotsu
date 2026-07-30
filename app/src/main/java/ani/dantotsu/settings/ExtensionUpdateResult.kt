package ani.dantotsu.settings

import androidx.annotation.StringRes
import ani.dantotsu.R
import eu.kanade.tachiyomi.extension.InstallStep

/**
 * Message for a finished extension update, or null when no terminal step was reached.
 *
 * The update stream completes on cancellation (Idle) and failure (Error) exactly as it does on
 * success, so completion on its own must never be reported as "updated".
 */
@StringRes
fun InstallStep?.updateResultMessage(): Int? = when (this) {
    InstallStep.Installed -> R.string.extension_updated
    InstallStep.Idle -> R.string.update_cancelled
    InstallStep.Error -> R.string.update_failed_short
    else -> null
}
