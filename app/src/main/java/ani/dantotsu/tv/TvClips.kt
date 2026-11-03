package ani.dantotsu.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.animethemes.AnimeThemeTrack
import ani.dantotsu.connections.animethemes.AnimeThemeVideo
import ani.dantotsu.media.TrailerWebView
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.launch

/**
 * The trailer and the openings and endings, a card each: the trailer into the YouTube app when
 * there's one here, else played here; a song's video played here, from AnimeThemes — picked from
 * its versions and copies first when it has more than one.
 */
@Composable
fun Clips(trailer: String?, themes: List<AnimeThemeTrack>, backdrop: String?) {
    val context = LocalContext.current
    val playable = themes.filter { choices(it, context).isNotEmpty() }
    if (trailer == null && playable.isEmpty()) return
    var choosing by remember { mutableStateOf<AnimeThemeTrack?>(null) }
    Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(R.string.tv_clips), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
        // Room for a focused card's growth at the start: these are wider than the other rows' covers.
        FocusRestingAt(16.dp) {
            LazyRow(
                contentPadding = PaddingValues(start = 16.dp, end = 48.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
            ) {
                if (trailer != null) item(key = "trailer") {
                    ClipCard(
                        image = "https://i.ytimg.com/vi/$trailer/hqdefault.jpg",
                        title = stringResource(R.string.tv_trailer),
                        subtitle = null,
                        youtube = true,
                    ) { playTrailer(context, trailer) }
                }
                items(playable, key = { it.slug + (it.group ?: "") }) { track ->
                    ClipCard(image = backdrop, title = track.slug, subtitle = track.displayTitle()) {
                        val options = choices(track, context)
                        if (options.size == 1) TvClipActivity.play(context, options[0].second.link, track.displayTitle())
                        else choosing = track
                    }
                }
            }
        }
    }
    choosing?.let { track ->
        ThemeVersionDialog(track, choices(track, context), onDismiss = { choosing = null }) { video ->
            choosing = null
            TvClipActivity.play(context, video.link, track.displayTitle())
        }
    }
}

/**
 * Every copy of a theme to play, as it'd be told apart: the version (when there's more than one),
 * the episodes it ran over, and the copy itself ("1080p Blu-ray (no credits)"); the version most
 * episodes ran first, each version's copies without credits and sharpest first. NSFW cuts left out.
 */
private fun choices(track: AnimeThemeTrack, context: Context): List<Pair<String, AnimeThemeVideo>> =
    track.versions.filter { !it.nsfw }.flatMap { version ->
        val head = listOfNotNull(
            version.version?.takeIf { track.versions.size > 1 }?.let { context.getString(R.string.theme_version, it) },
            version.episodesLabel(context),
            context.getString(R.string.spoiler_tag).takeIf { version.spoiler },
        ).joinToString(" · ")
        version.videos.sortedWith(compareByDescending<AnimeThemeVideo> { it.nc }.thenByDescending { it.resolution ?: 0 })
            .map { video -> listOf(head, video.label(context)).filter { it.isNotBlank() }.joinToString(" · ") to video }
    }

/** Which copy of a theme to play: the best first, in focus. */
@Composable
private fun ThemeVersionDialog(
    track: AnimeThemeTrack,
    options: List<Pair<String, AnimeThemeVideo>>,
    onDismiss: () -> Unit,
    onPick: (AnimeThemeVideo) -> Unit,
) {
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    TvDialog(onDismissRequest = onDismiss) {
        androidx.tv.material3.Surface(shape = CARD_SHAPE, modifier = Modifier.width(520.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(track.slug, style = MaterialTheme.typography.headlineSmall)
                Text(
                    track.displayTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                FocusRestingAt(48.dp) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(options.size) { i ->
                            val (label, video) = options[i]
                            val pick = { onPick(video) }
                            androidx.tv.material3.ListItem(
                                selected = false,
                                onClick = pick,
                                headlineContent = { Text(label) },
                                modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).pointerClick(onClick = pick),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun playTrailer(context: Context, youtubeId: String) {
    // The YouTube app plays it best, remote and all; without one, played here.
    if (!openInApp(context, "https://www.youtube.com/watch?v=$youtubeId")) {
        TvClipActivity.youtube(context, youtubeId)
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ClipCard(image: String?, title: String, subtitle: String?, youtube: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(240.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).pointerClick(onClick = onClick),
            shape = CardDefaults.shape(CARD_SHAPE),
            // Less than a cover's growth: a tenth of this width ran past the row's edges and was cut.
            scale = CardDefaults.scale(focusedScale = 1.05f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(FOCUS_OUTLINE, MaterialTheme.colorScheme.primary), shape = CARD_SHAPE),
            ),
        ) {
            Box(Modifier.fillMaxSize().clip(CARD_SHAPE)) {
                GlideImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
                if (youtube) Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                    // YouTube's own mark: the white play triangle through a red rounded box. The
                    // drawable has the triangle cut out, so white shows through it from beneath.
                    Box(Modifier.size(16.dp).background(Color.White))
                    Icon(
                        painterResource(R.drawable.ic_youtube),
                        contentDescription = "YouTube",
                        tint = Color(0xFFFF0033),
                        modifier = Modifier.size(52.dp),
                    )
                } else Icon(
                    painterResource(R.drawable.ic_round_play_arrow_24),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                )
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * A clip, full screen: a theme's video under TV mode's own player controls — the timeline and the
 * playback buttons, as an episode has them, without what only an episode has — or a YouTube
 * trailer in its embedded player. The remote works as in the player: OK pauses and shows the
 * controls, left and right seek while they're hidden, back hides them, then leaves.
 */
class TvClipActivity : AppCompatActivity(), TvScreen {

    private var player: ExoPlayer? = null
    private var web: WebView? = null
    private var controller: TvPlayerController? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    @OptIn(UnstableApi::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        setContentView(root)
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val youtube = intent.getStringExtra(EXTRA_YOUTUBE)
        if (youtube != null) {
            web = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                loadDataWithBaseURL(TrailerWebView.BASE_URL, TrailerWebView.embedHtml(youtube), "text/html", "utf-8", null)
            }
            root.addView(web, match)
            web?.requestFocus()
            return
        }
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        root.addView(PlayerView(this).apply {
            this.player = exo
            useController = false
        }, match)
        val controls = TvPlayerController(object : TvPlayerController.Actions {
            override fun playPause() { if (exo.isPlaying) exo.pause() else exo.play() }
            override fun seekBy(deltaMs: Long) {
                val end = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0, end))
            }
            override fun seekTo(positionMs: Long) = exo.seekTo(positionMs.coerceAtLeast(0))
            // What only an episode has: not shown for a clip.
            override fun previous() {}
            override fun next() {}
            override fun audio() {}
            override fun subtitles() {}
            override fun server() {}
            override fun skip() {}
            override fun playNext() {}
            override fun cancelNext() {}
            override fun cast() {}
            override fun handoff() {}
            override fun episodes() {}
            override fun settings() {}
            override fun setSubtitleDelay(ms: Long) {}
            override fun subtitleDelayDone() {}
        }, simple = true).also { controller = it }
        controls.title = intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty()
        root.addView(androidx.compose.ui.platform.ComposeView(this).apply {
            setContent { TvTheme(rateLimit = false, downloads = false) { TvPlayerOverlay(controls) } }
        }, match)
        // What the controls show, from the player, twice a second.
        lifecycleScope.launch {
            while (true) {
                controls.playing = exo.isPlaying
                controls.position = exo.currentPosition
                controls.duration = exo.duration.takeIf { it > 0 } ?: 0L
                controls.buffered = exo.bufferedPercentage / 100f
                kotlinx.coroutines.delay(500)
            }
        }
        exo.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                ani.dantotsu.util.Logger.log("TvClipActivity: ${error.errorCodeName}: ${error.message}")
                ani.dantotsu.toast(getString(R.string.tv_clip_failed))
                finish()
            }
        })
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.play()
    }

    /** The remote while the controls are hidden, as in the episode player; with them up, they take it. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val controls = controller ?: return super.dispatchKeyEvent(event)
        val exo = player ?: return super.dispatchKeyEvent(event)
        val up = event.action == android.view.KeyEvent.ACTION_UP
        controls.poke()
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (up) controls.act { playPause() }
                return true
            }
            android.view.KeyEvent.KEYCODE_BACK -> if (controls.visible) {
                if (up) controls.hide()
                return true
            }
        }
        if (controls.visible) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!up) {
                    val step = ani.dantotsu.settings.saving.PrefManager.getVal<Int>(ani.dantotsu.settings.saving.PrefName.SeekTime) * 1000L
                    controls.act { seekBy(if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -step else step) }
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_BUTTON_A -> {
                if (up) {
                    if (exo.isPlaying) exo.pause()
                    controls.show()
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (up) controls.show()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        web?.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_YOUTUBE = "youtube"

        fun play(context: Context, url: String, title: String) {
            context.startActivity(Intent(context, TvClipActivity::class.java).putExtra(EXTRA_URL, url).putExtra(Intent.EXTRA_TITLE, title))
        }

        fun youtube(context: Context, id: String) {
            context.startActivity(Intent(context, TvClipActivity::class.java).putExtra(EXTRA_YOUTUBE, id))
        }
    }
}
