package ani.dantotsu.connections.mangabaka

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Catches the `dantotsu://mangabaka?code=…` redirect and completes the OAuth token exchange. */
class Login : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        val data: Uri? = intent?.data
        Logger.log("MangaBaka Login got: $data")

        val error = data?.getQueryParameter("error")
        val code = data?.getQueryParameter("code")
        when {
            error != null -> {
                toast("MangaBaka: $error — ${data.getQueryParameter("error_description") ?: ""}")
                startMainActivity(this)
            }
            code == null -> {
                toast("MangaBaka login: no code in redirect ($data)")
                startMainActivity(this)
            }
            else -> {
                snackString("Logging in to MangaBaka…")
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = MangaBaka.handleAuthCode(code)
                    snackString(if (ok) "Successfully logged into MangaBaka" else "MangaBaka login failed")
                    launch(Dispatchers.Main) { startMainActivity(this@Login) }
                }
            }
        }
    }
}
