package ani.dantotsu.connections.handoff

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.launch

/**
 * Headless entry point for [dantotsu://handoff] deep links — reached both when another device's
 * QR code is scanned with a camera app and when a received-handoff notification is tapped. It
 * decodes the [HandoffPayload] and forwards to [HandoffNavigator], then finishes.
 */
class HandoffDeepLinkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        val payload = uri?.let { HandoffPayload.fromDeepLink(it) }
        if (payload == null) {
            snackString(getString(R.string.handoff_media_not_found))
            finish()
            return
        }
        lifecycleScope.launch {
            HandoffNavigator.navigate(this@HandoffDeepLinkActivity, payload)
            finish()
        }
    }
}
