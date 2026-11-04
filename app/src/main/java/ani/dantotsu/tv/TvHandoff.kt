package ani.dantotsu.tv

import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.handoff.CloudHandoff
import ani.dantotsu.connections.handoff.HandoffManager
import ani.dantotsu.connections.handoff.HandoffPayload
import ani.dantotsu.connections.handoff.HandoffQr
import ani.dantotsu.connections.handoff.transport.HandoffEndpoint
import ani.dantotsu.connections.handoff.transport.NearbyTransport
import ani.dantotsu.snackString
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.delay

/**
 * "Continue on another device" for a remote: the nearby devices running Dantotsu, a row each, and
 * a code for anything out of reach — its QR for a phone's camera, and the same code spelt out to
 * type in. The phone's sheet, through the same [HandoffManager] and [CloudHandoff]; only drawn for
 * a TV. Where there's no local discovery (switched off, or a virtual device), only the code.
 */
@Composable
fun TvHandoffSendDialog(payload: HandoffPayload, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val local = remember { HandoffManager.localDiscoveryActive(context) }
    val manager = remember { HandoffManager(context.applicationContext) }
    var devices by remember { mutableStateOf<List<HandoffEndpoint>>(emptyList()) }
    var sendingTo by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var uploading by remember { mutableStateOf(false) }

    val start = {
        if (!started) {
            started = true
            manager.startSending(payload, object : HandoffManager.Listener {
                override fun onEndpointsChanged(endpoints: List<HandoffEndpoint>) { devices = endpoints }
                override fun onSent() {
                    snackString(context.getString(R.string.handoff_sent, sendingTo.orEmpty()))
                    onDismiss()
                }
                override fun onError(message: String) {
                    sendingTo = null
                    snackString(message)
                }
            })
        }
    }
    // Nearby needs its permissions; the local network doesn't, so finding devices starts either way.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { start() }
    LaunchedEffect(Unit) {
        if (!local) return@LaunchedEffect
        val missing = NearbyTransport.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) start() else permissions.launch(missing.toTypedArray())
    }
    DisposableEffect(Unit) { onDispose { manager.stop() } }

    // One upload serves both: the QR carries the code in its link, and the code can be typed.
    val showCode = {
        if (!uploading && code == null) {
            uploading = true
            CloudHandoff.upload(payload) { uploaded ->
                uploading = false
                if (uploaded == null) snackString(context.getString(R.string.handoff_code_upload_failed))
                else {
                    code = uploaded
                    qr = HandoffQr.encode(payload.toDeepLink(uploaded), 600)?.asImageBitmap()
                }
            }
        }
    }

    val first = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE) {
            Row(Modifier.padding(28.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.width(420.dp)) {
                    Text(stringResource(R.string.continue_on_another_device), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(if (local) R.string.handoff_media_title else R.string.handoff_media_title_virtual, payload.title.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    )
                    if (local) {
                        if (devices.isEmpty()) Text(
                            stringResource(if (started) R.string.handoff_searching else R.string.handoff_permission_needed),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                        )
                        FocusRestingAt(48.dp) {
                            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                                items(devices, key = { it.id }) { device ->
                                    val send = {
                                        if (sendingTo == null) {
                                            sendingTo = device.name
                                            manager.sendTo(device.id)
                                        }
                                    }
                                    ListItem(
                                        selected = false,
                                        onClick = send,
                                        headlineContent = { Text(device.name) },
                                        supportingContent = if (sendingTo == device.name) ({ Text(stringResource(R.string.handoff_sending, device.name)) }) else null,
                                        leadingContent = {
                                            Icon(painterResource(R.drawable.ic_round_devices_outline_24), contentDescription = null, modifier = Modifier.size(24.dp))
                                        },
                                        modifier = (if (device == devices.first()) Modifier.focusRequester(first) else Modifier)
                                            .pointerClick(onClick = send),
                                    )
                                }
                            }
                        }
                    }
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (code == null) Button(
                            onClick = showCode,
                            modifier = (if (!local || devices.isEmpty()) Modifier.focusRequester(first) else Modifier).pointerClick(onClick = showCode),
                        ) { Text(stringResource(if (uploading) R.string.handoff_uploading_code else R.string.tv_handoff_show_code)) }
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.pointerClick(onClick = onDismiss)) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
                val shownCode = code
                if (shownCode != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Dark on white whatever the theme: a scanner needs the contrast.
                    Box(
                        Modifier.size(240.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        qr?.let { Image(it, contentDescription = shownCode, modifier = Modifier.fillMaxSize()) }
                    }
                    Text(
                        shownCode,
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Text(
                        stringResource(R.string.tv_handoff_code_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(240.dp).padding(top = 6.dp),
                    )
                }
            }
        }
    }
    // On the first device as one appears, else the code button; a frame on, for the dialog's window.
    LaunchedEffect(devices.isNotEmpty(), code) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}

/**
 * A handoff arriving in TV mode: who it's from, what it is and where they were, with Open in focus
 * (the phone's swipe-away banner has nothing for a remote to press). Back or Dismiss puts it away;
 * left alone, it goes by itself after a while, as the phone's does.
 */
object TvHandoffPrompt {

    private const val AUTO_DISMISS_MS = 30_000L

    fun show(activity: ComponentActivity, payload: HandoffPayload, onOpen: () -> Unit) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<ComposeView>(TAG)?.let { content.removeView(it) }
        val view = ComposeView(activity).apply { tag = TAG }
        val close = { content.removeView(view) }
        view.setContent {
            TvTheme(rateLimit = false, downloads = false) {
                Prompt(payload, onOpen = { close(); onOpen() }, onDismiss = { close() })
            }
        }
        content.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private const val TAG = "tvHandoffPrompt"

    @OptIn(ExperimentalGlideComposeApi::class)
    @Composable
    private fun Prompt(payload: HandoffPayload, onOpen: () -> Unit, onDismiss: () -> Unit) {
        val open = remember { FocusRequester() }
        BackHandler(onBack = onDismiss)
        LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { open.requestFocus() }
            delay(AUTO_DISMISS_MS)
            onDismiss()
        }
        Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.TopEnd) {
            Surface(shape = CARD_SHAPE, modifier = Modifier.width(480.dp)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlideImage(
                        model = tvCover(payload.cover),
                        requestBuilderTransform = coverFallback(payload.cover),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(72.dp).height(102.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Column(Modifier.padding(start = 18.dp)) {
                        Text(
                            stringResource(R.string.handoff_incoming_from, payload.senderName),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            payload.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        progressLine(payload)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onOpen, modifier = Modifier.focusRequester(open).pointerClick(onClick = onOpen)) {
                                Text(stringResource(R.string.handoff_open))
                            }
                            OutlinedButton(onClick = onDismiss, modifier = Modifier.pointerClick(onClick = onDismiss)) {
                                Text(stringResource(R.string.tv_handoff_dismiss))
                            }
                        }
                    }
                }
            }
        }
    }

    /** "Episode 5 · 12:34" (or "Chapter 3 · page 12"), or null for a media-only handoff. */
    @Composable
    private fun progressLine(payload: HandoffPayload): String? {
        val number = payload.number ?: return null
        val unit = if (payload.isAnime) stringResource(R.string.tv_episode_n, number)
        else stringResource(R.string.tv_handoff_chapter, number)
        val at = if (payload.isAnime) payload.positionMs?.takeIf { it > 0 }?.let { ms ->
            val s = ms / 1000
            if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        } else payload.page?.let { stringResource(R.string.handoff_page_label, it.toString()) }
        return listOfNotNull(unit, at).joinToString("  ·  ")
    }
}
