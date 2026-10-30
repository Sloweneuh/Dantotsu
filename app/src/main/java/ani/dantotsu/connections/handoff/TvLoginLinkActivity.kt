package ani.dantotsu.connections.handoff

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.sync.SyncIdentity
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.launch

/**
 * The phone's side of [TvLogin]: a TV's sign-in QR, scanned by the camera app or Dantotsu's own
 * scanner, lands here. One question — sign that TV in as this account? — and the session goes.
 */
class TvLoginLinkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)

        val request = TvLogin.parse(intent?.data)
        if (request == null) {
            snackString(getString(R.string.tvlogin_invalid))
            finish()
            return
        }
        val token = Anilist.token
        if (token.isNullOrBlank()) {
            snackString(getString(R.string.tvlogin_need_login))
            finish()
            return
        }
        // With cloud sync linked, the TV joins it too: the same settings, and the extensions.
        val syncCode = SyncIdentity.displayCode()
        val name = Anilist.username ?: "AniList"
        customAlertDialog().apply {
            setTitle(getString(R.string.tvlogin_title))
            setMessage(
                getString(R.string.tvlogin_body, request.tvName, name) +
                    if (syncCode != null) "\n\n" + getString(R.string.tvlogin_body_sync) else ""
            )
            setPosButton(getString(R.string.tvlogin_confirm)) {
                lifecycleScope.launch {
                    val sent = TvLogin.send(request, TvLogin.Grant(token, syncCode))
                    snackString(getString(if (sent) R.string.tvlogin_sent else R.string.tvlogin_failed, request.tvName))
                    finish()
                }
            }
            setNegButton(getString(R.string.cancel)) { finish() }
            setOnCancelListener { finish() }
            show()
        }
    }
}
