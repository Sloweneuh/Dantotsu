package ani.dantotsu.connections.handoff

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.launch
import java.io.Serializable

/**
 * Headless entry point for handoffs that arrive via an Intent — a [dantotsu://handoff] deep link
 * (another device's QR scanned by a camera app) or a tapped received-handoff notification. It
 * resolves the [HandoffPayload] and forwards to [HandoffNavigator], then finishes.
 *
 * The notification path passes the full payload directly as [EXTRA_PAYLOAD] (preserving
 * sourceMedia); deep links carry only the lightweight data and may upgrade via a cloud code.
 */
class HandoffDeepLinkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)

        // In-process payload (notification): already complete, incl. sourceMedia — use it directly.
        val extraPayload =
            @Suppress("DEPRECATION") intent?.getSerializableExtra(EXTRA_PAYLOAD) as? HandoffPayload
        if (extraPayload != null) {
            navigate(extraPayload)
            return
        }

        val uri = intent?.data
        val payload = uri?.let { HandoffPayload.fromDeepLink(it) }
        if (uri == null || payload == null) {
            snackString(getString(R.string.handoff_media_not_found))
            finish()
            return
        }
        // If the QR carried a cloud code, resolve the full payload (with the exact source entry)
        // for a smooth open; otherwise (or if it's unreachable) use the embedded lightweight one.
        val code = uri.getQueryParameter(HandoffPayload.QUERY_CODE)
        if (code != null) {
            CloudHandoff.fetch(code, consume = false) { full -> navigate(full ?: payload) }
        } else navigate(payload)
    }

    private fun navigate(payload: HandoffPayload) {
        lifecycleScope.launch {
            HandoffNavigator.navigate(this@HandoffDeepLinkActivity, payload)
            finish()
        }
    }

    companion object {
        /** Serializable [HandoffPayload] extra for the in-process (notification) path. */
        const val EXTRA_PAYLOAD = "handoffPayload"
    }
}
