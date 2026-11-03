package ani.dantotsu.tv

import android.content.Context
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.discord.RemoteAuth
import ani.dantotsu.connections.handoff.HandoffQr
import ani.dantotsu.initActivity
import ani.dantotsu.themes.ThemeManager
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Discord on the TV, by its own "scan to log in": a code to scan with the Discord app on a phone,
 * confirmed there — the TV never asks for a password. The sign-in page remains, for a mouse and a
 * keyboard, if Discord insists on a captcha.
 */
class TvDiscordSignInActivity : AppCompatActivity(), TvScreen {

    private val state = mutableStateOf<DiscordSignInState>(DiscordSignInState.Connecting)
    private var auth: RemoteAuth? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent {
            TvTheme {
                DiscordSignIn(
                    state.value,
                    onRetry = ::begin,
                    onWebPage = {
                        startActivity(Intent(this, ani.dantotsu.connections.discord.Login::class.java))
                        finish()
                    },
                )
            }
        }
        begin()
    }

    private fun begin() {
        auth?.stop()
        state.value = DiscordSignInState.Connecting
        auth = RemoteAuth(object : RemoteAuth.Listener {
            override fun onCode(url: String) { state.value = DiscordSignInState.Code(url) }
            override fun onScanned(username: String, avatarUrl: String?) { state.value = DiscordSignInState.Scanned(username, avatarUrl) }
            override fun onFailed(reason: RemoteAuth.Reason) { state.value = DiscordSignInState.Failed(reason) }
            override fun onToken(token: String) {
                state.value = DiscordSignInState.SigningIn
                lifecycleScope.launch {
                    if (Discord.completeLogin(this@TvDiscordSignInActivity, token)) {
                        state.value = DiscordSignInState.Done(
                            ani.dantotsu.settings.saving.PrefManager.getVal(ani.dantotsu.settings.saving.PrefName.DiscordUserName, "")
                        )
                        // Long enough to read who it signed in as.
                        delay(1_500L)
                        finish()
                    } else state.value = DiscordSignInState.Failed(RemoteAuth.Reason.ERROR)
                }
            }
        }).also { it.start() }
    }

    override fun onDestroy() {
        auth?.stop()
        super.onDestroy()
    }
}

/** Where the scan has got to. */
internal sealed interface DiscordSignInState {
    data object Connecting : DiscordSignInState
    data class Code(val url: String) : DiscordSignInState
    data class Scanned(val name: String, val avatar: String?) : DiscordSignInState
    data object SigningIn : DiscordSignInState
    data class Done(val name: String) : DiscordSignInState
    data class Failed(val reason: RemoteAuth.Reason) : DiscordSignInState
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DiscordSignIn(state: DiscordSignInState, onRetry: () -> Unit, onWebPage: () -> Unit) {
    val retry = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(460.dp)) {
                Text(stringResource(R.string.tv_discord_sign_in), style = MaterialTheme.typography.displaySmall)
                when (state) {
                    is DiscordSignInState.Done -> Text(
                        stringResource(R.string.tv_sign_in_done, state.name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    is DiscordSignInState.Failed -> {
                        Text(
                            stringResource(
                                when (state.reason) {
                                    RemoteAuth.Reason.CANCELLED -> R.string.tv_discord_cancelled
                                    RemoteAuth.Reason.TIMED_OUT -> R.string.tv_discord_timed_out
                                    RemoteAuth.Reason.CAPTCHA -> R.string.tv_discord_captcha
                                    RemoteAuth.Reason.ERROR -> R.string.tv_discord_failed
                                }
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onRetry, modifier = Modifier.focusRequester(retry).pointerClick(onClick = onRetry)) {
                                Text(stringResource(R.string.tv_discord_new_code))
                            }
                            OutlinedButton(onClick = onWebPage, modifier = Modifier.pointerClick(onClick = onWebPage)) {
                                Text(stringResource(R.string.tv_discord_web_page))
                            }
                        }
                        LaunchedEffect(Unit) {
                            androidx.compose.runtime.withFrameNanos { }
                            runCatching { retry.requestFocus() }
                        }
                    }
                    is DiscordSignInState.Scanned -> {
                        val name = state.name
                        val avatar = state.avatar
                        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (avatar != null) GlideImage(
                                model = avatar,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp).clip(CircleShape),
                            )
                            Text(
                                stringResource(R.string.tv_discord_confirm_on_phone, name),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = if (avatar != null) 14.dp else 0.dp),
                            )
                        }
                    }
                    DiscordSignInState.SigningIn -> Text(
                        stringResource(R.string.tv_discord_signing_in),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    DiscordSignInState.Connecting, is DiscordSignInState.Code -> {
                        DiscordStep(1, stringResource(R.string.tv_discord_step_open))
                        DiscordStep(2, stringResource(R.string.tv_discord_step_scan))
                        DiscordStep(3, stringResource(R.string.tv_discord_step_confirm))
                    }
                }
                Text(
                    stringResource(R.string.tv_discord_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
            // Dark on white whatever the theme: a scanner needs the contrast.
            val url = (state as? DiscordSignInState.Code)?.url
            val qr = remember(url) { url?.let { HandoffQr.encode(it, 600)?.asImageBitmap() } }
            Box(
                Modifier.size(300.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) Image(qr, contentDescription = stringResource(R.string.tv_discord_sign_in), modifier = Modifier.fillMaxSize())
                else androidx.tv.material3.Icon(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_discord),
                    contentDescription = null,
                    tint = Color(0xFF5865F2),
                    modifier = Modifier.size(96.dp),
                )
            }
        }
    }
}

@Composable
private fun DiscordStep(number: Int, text: String) {
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
