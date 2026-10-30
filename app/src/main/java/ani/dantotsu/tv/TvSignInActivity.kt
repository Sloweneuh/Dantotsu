package ani.dantotsu.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.handoff.HandoffQr
import ani.dantotsu.connections.handoff.TvLogin
import ani.dantotsu.connections.sync.SyncIdentity
import ani.dantotsu.initActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Signing in from the couch: a QR the phone scans (see [TvLogin]), and the steps beside it. The
 * code is checked every few seconds and replaced when it expires; once the phone has sent its
 * session, home opens again signed in.
 */
class TvSignInActivity : AppCompatActivity(), TvScreen {

    private var session by mutableStateOf(TvLogin.newSession())
    private var signingIn by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent { TvTheme { SignIn(session.link(TvLogin.deviceName()), signingIn) } }

        lifecycleScope.launch {
            while (isActive && signingIn == null) {
                if (session.expired) session = TvLogin.newSession()
                val grant = withContext(Dispatchers.IO) { TvLogin.receive(session) }
                if (grant != null) {
                    apply(grant)
                    return@launch
                }
                delay(POLL_MS)
            }
        }
    }

    /**
     * Signs in where the app is, rather than starting it over: what a cold start would do with the
     * new account — identify it, then pull what cloud sync holds for it — done here, then home again.
     * (Ending the process to restart it left the Mi TV on a black screen: it never relaunched.)
     */
    private suspend fun apply(grant: TvLogin.Grant) {
        Anilist.token = grant.anilistToken
        PrefManager.setVal(PrefName.AnilistToken, grant.anilistToken)
        val name = withContext(Dispatchers.IO) {
            grant.syncCode?.let { code ->
                if (SyncIdentity.linkWithCode(code)) runCatching { ani.dantotsu.connections.sync.SyncMigration.run() }
            }
            if (runCatching { Anilist.query.getUserData() }.getOrDefault(false)) {
                runCatching {
                    ani.dantotsu.connections.sync.CloudSync.pullInBackground()
                    ani.dantotsu.connections.sync.ExtensionSettingsSync.pullInBackground()
                    ani.dantotsu.connections.sync.ProgressSync.pullInBackground()
                    ani.dantotsu.connections.sync.ExtensionSync.checkInBackground()
                }
            }
            Anilist.username
        }
        signingIn = name ?: ""
        // Long enough to read who it signed in as.
        delay(SHOW_DONE_MS)
        startActivity(
            Intent(this, TvHomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private companion object {
        const val POLL_MS = 3_000L
        const val SHOW_DONE_MS = 1_500L
    }
}

@Composable
private fun SignIn(link: String, signingIn: String?) {
    val qr = remember(link) { HandoffQr.encode(link, 600)?.asImageBitmap() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(420.dp)) {
                Text(stringResource(R.string.tv_sign_in_title), style = MaterialTheme.typography.displaySmall)
                if (signingIn != null) {
                    Text(
                        if (signingIn.isNotBlank()) stringResource(R.string.tv_sign_in_done, signingIn)
                        else stringResource(R.string.tv_sign_in_done_unnamed),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                } else {
                    Step(1, stringResource(R.string.tv_sign_in_step_scan))
                    Step(2, stringResource(R.string.tv_sign_in_step_confirm))
                    Text(
                        stringResource(R.string.tv_sign_in_waiting),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 28.dp),
                    )
                }
            }
            // Dark on white whatever the theme: a scanner needs the contrast.
            Box(
                Modifier.size(300.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) Image(qr, contentDescription = stringResource(R.string.tv_sign_in_title), modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.Top) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                .padding(horizontal = 11.dp, vertical = 3.dp),
        )
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 14.dp, top = 2.dp))
    }
}
