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

/**
 * The trailer and the openings and endings, a card each: the trailer into the YouTube app when
 * there's one here, else played here; a song's video played here, from AnimeThemes.
 */
@Composable
fun Clips(trailer: String?, themes: List<AnimeThemeTrack>, backdrop: String?) {
    val context = LocalContext.current
    val playable = themes.mapNotNull { track -> bestVideo(track)?.let { track to it } }
    if (trailer == null && playable.isEmpty()) return
    Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(R.string.tv_clips), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
        FocusRestingAt(8.dp) {
            LazyRow(
                contentPadding = PaddingValues(start = 8.dp, end = 48.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
            ) {
                if (trailer != null) item(key = "trailer") {
                    ClipCard(
                        image = "https://i.ytimg.com/vi/$trailer/hqdefault.jpg",
                        title = stringResource(R.string.tv_trailer),
                        subtitle = null,
                    ) { playTrailer(context, trailer) }
                }
                items(playable, key = { it.first.slug + (it.first.group ?: "") }) { (track, video) ->
                    ClipCard(image = backdrop, title = track.slug, subtitle = track.displayTitle()) {
                        TvClipActivity.play(context, video.link, track.displayTitle())
                    }
                }
            }
        }
    }
}

/**
 * The copy of a theme to play: from its first cut (the one most episodes ran), without credits
 * over it when there's such a copy, at the best resolution. Spoilers and NSFW cuts are left out.
 */
private fun bestVideo(track: AnimeThemeTrack): AnimeThemeVideo? {
    val version = track.versions.firstOrNull { !it.spoiler && !it.nsfw } ?: return null
    return version.videos.maxWithOrNull(compareBy<AnimeThemeVideo> { it.nc }.thenBy { it.resolution ?: 0 })
}

private fun playTrailer(context: Context, youtubeId: String) {
    // The YouTube app plays it best, remote and all; without one, played here.
    if (!openInApp(context, "https://www.youtube.com/watch?v=$youtubeId")) {
        TvClipActivity.youtube(context, youtubeId)
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ClipCard(image: String?, title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.width(240.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).pointerClick(onClick = onClick),
            shape = CardDefaults.shape(CARD_SHAPE),
            scale = CardDefaults.scale(focusedScale = FOCUSED_SCALE),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(FOCUS_OUTLINE, MaterialTheme.colorScheme.primary), shape = CARD_SHAPE),
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                GlideImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
                Icon(
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
 * A clip, full screen: a theme's video in a plain player the D-pad works (OK shows its controls,
 * left and right seek), or a YouTube trailer in its embedded player. Back leaves.
 */
class TvClipActivity : AppCompatActivity(), TvScreen {

    private var player: ExoPlayer? = null
    private var web: WebView? = null

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
        val view = PlayerView(this).apply {
            this.player = exo
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerAutoShow = true
        }
        root.addView(view, match)
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.play()
        view.requestFocus()
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
