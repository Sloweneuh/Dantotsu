package ani.dantotsu.connections.comick

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.settings.SettingsAccountActivity
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.launch

/**
 * Catches the `<applicationId>:/oauth/comick` redirect and completes the token exchange, then
 * returns to the Accounts screen.
 */
class ComickLogin : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        val redirect = intent?.data
        lifecycleScope.launch {
            val message = when (Comick.handleRedirect(redirect)) {
                Comick.LoginResult.SUCCESS -> R.string.comick_login_success
                Comick.LoginResult.DENIED -> R.string.comick_login_denied
                Comick.LoginResult.FAILED -> R.string.comick_login_failed
            }
            snackString(getString(message))
            // The Custom Tab sits above the Accounts screen in this task, so finishing alone would
            // land back in the browser. Clearing down to the screen pops it; that screen refreshes
            // on resume.
            startActivity(
                Intent(this@ComickLogin, SettingsAccountActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
    }
}
