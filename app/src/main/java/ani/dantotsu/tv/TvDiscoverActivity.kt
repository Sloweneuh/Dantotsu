package ani.dantotsu.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.media.discover.AniListAnimeDiscovery
import ani.dantotsu.media.discover.DiscoverListAdd
import ani.dantotsu.media.discover.DiscoveryBuildResult
import ani.dantotsu.media.discover.DiscoveryQueue
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

/**
 * The anime discovery queue from the couch: one pick at a time, large, over its banner, with why it
 * was picked and the verdicts as buttons. The same queue as the phone's (same picks, same saved
 * progress, same exclusions); only the screen is the TV's.
 */
class TvDiscoverActivity : AppCompatActivity(), TvScreen {

    private val source = AniListAnimeDiscovery

    private sealed interface Screen {
        data object Loading : Screen
        data class Message(val title: String, val body: String?, val retry: Boolean) : Screen
        data class Pick(val queue: DiscoveryQueue, val item: DiscoveryQueue.Item) : Screen
        data class Complete(val queue: DiscoveryQueue) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Loading)

    /** The queue's picks as AniList media (banner, synopsis, genres), fetched together once. */
    private var details by mutableStateOf<Map<Long, Media>>(emptyMap())
    private var busy by mutableStateOf(false)

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // TV mode is dark whatever the system says: a bright screen glares across a dark room.
        delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent {
            TvTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when (val shown = screen) {
                        Screen.Loading -> Centered(stringResource(R.string.tv_discover_building), null)
                        is Screen.Message -> Centered(shown.title, shown.body) {
                            if (shown.retry) {
                                val retry = { lifecycleScope.launch { build(jitter = false) }; Unit }
                                Button(onClick = retry, modifier = Modifier.pointerClick(onClick = retry)) {
                                    Text(stringResource(R.string.discover_retry))
                                }
                            }
                        }
                        is Screen.Pick -> PickScreen(shown.queue, shown.item, details[shown.item.id])
                        is Screen.Complete -> CompleteScreen(shown.queue)
                    }
                }
            }
        }
        lifecycleScope.launch { start() }
    }

    /** A queue left part-way carries on where it was; otherwise a new one is built. */
    private suspend fun start() {
        val account = withContext(Dispatchers.IO) { source.accountId() }
        if (account == null) {
            screen = Screen.Message(getString(source.needsLoginMessage), null, retry = false)
            return
        }
        val saved = withContext(Dispatchers.IO) { source.load(account) }
        when {
            saved == null -> build(jitter = false)
            saved.isComplete -> screen = Screen.Complete(saved)
            else -> show(saved, saved.next())
        }
    }

    private suspend fun build(jitter: Boolean, carrySkipped: List<Long> = emptyList()) {
        screen = Screen.Loading
        val result = withContext(Dispatchers.IO) {
            runCatching { source.build(this@TvDiscoverActivity, jitter, carrySkipped) }
                .getOrDefault(DiscoveryBuildResult.Failed)
        }
        screen = when (result) {
            is DiscoveryBuildResult.Ready -> {
                show(result.queue, result.queue.next())
                return
            }
            is DiscoveryBuildResult.ColdStart -> Screen.Message(getString(result.title), result.body, retry = false)
            DiscoveryBuildResult.Empty -> Screen.Message(getString(R.string.discover_empty_title), null, retry = true)
            DiscoveryBuildResult.ProfileStale, DiscoveryBuildResult.Failed ->
                Screen.Message(getString(R.string.discover_failed_title), getString(source.failedBody), retry = true)
        }
    }

    private suspend fun show(queue: DiscoveryQueue, item: DiscoveryQueue.Item?) {
        if (item == null) {
            screen = Screen.Complete(queue)
            return
        }
        screen = Screen.Pick(queue, item)
        val missing = queue.items.map { it.id }.filter { it !in details }
        if (missing.isNotEmpty()) {
            val fetched = withContext(Dispatchers.IO) {
                runCatching { Anilist.query.getMediaBatch(missing.map { it.toInt() }, mediaType = "ANIME") }.getOrNull()
            }
            if (fetched != null) details = details + fetched.associateBy { it.id.toLong() }
        }
    }

    /** A verdict on the pick on screen, saved with the queue the way the phone's sheet saves it. */
    private fun answer(queue: DiscoveryQueue, item: DiscoveryQueue.Item, action: DiscoveryQueue.Action) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            if (action == DiscoveryQueue.Action.ADDED) {
                val result = withContext(Dispatchers.IO) { source.add(item) }
                snackString(
                    when (result) {
                        DiscoverListAdd.Result.AddedToAnilist -> getString(R.string.discover_added_anilist)
                        is DiscoverListAdd.Result.AlreadyOnAnilist -> getString(R.string.discover_already_on_list, result.status)
                        DiscoverListAdd.Result.Failed -> getString(R.string.discover_add_failed, item.title)
                        else -> getString(R.string.discover_added_anilist)
                    }
                )
                if (result == DiscoverListAdd.Result.Failed) {
                    busy = false
                    return@launch
                }
                Refresh.all()
            }
            if (action != DiscoveryQueue.Action.SKIPPED) source.store.exclude(item.id)
            if (action == DiscoveryQueue.Action.HIDDEN) snackString(getString(R.string.discover_hidden))
            val updated = queue.withAction(item.id, action)
            source.store.save(updated)
            show(updated, updated.next(item.id))
            busy = false
        }
    }

    private fun open(media: Media) {
        startActivity(Intent(this, TvMediaActivity::class.java).putExtra("media", media as Serializable))
    }

    @OptIn(ExperimentalGlideComposeApi::class)
    @Composable
    private fun PickScreen(queue: DiscoveryQueue, item: DiscoveryQueue.Item, media: Media?) {
        val add = remember(item.id) { FocusRequester() }
        LaunchedEffect(item.id) { runCatching { add.requestFocus() } }
        Backdrop(media?.banner ?: media?.cover ?: item.cover, Modifier.fillMaxSize())
        Row(
            Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            GlideImage(
                model = media?.cover ?: item.cover,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(220.dp).aspectRatio(2f / 3f).clip(CARD_SHAPE),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.discover_sheet_title, queue.position(item.id), queue.items.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                FittedTitle(media?.userPreferredName ?: item.title, Modifier.padding(top = 6.dp))
                if (media != null) Text(
                    metaLine(media),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                reasonLine(item)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
                if (media != null) Text(
                    plainDescription(media),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val added = { answer(queue, item, DiscoveryQueue.Action.ADDED) }
                    val skipped = { answer(queue, item, DiscoveryQueue.Action.SKIPPED) }
                    val hidden = { answer(queue, item, DiscoveryQueue.Action.HIDDEN) }
                    Button(onClick = added, modifier = Modifier.focusRequester(add).pointerClick(onClick = added)) {
                        Text(stringResource(source.addLabel))
                    }
                    OutlinedButton(onClick = skipped, modifier = Modifier.pointerClick(onClick = skipped)) {
                        Text(stringResource(R.string.discover_skip))
                    }
                    OutlinedButton(onClick = hidden, modifier = Modifier.pointerClick(onClick = hidden)) {
                        Text(stringResource(R.string.discover_not_interested))
                    }
                    if (media != null) {
                        val details = { open(media) }
                        OutlinedButton(onClick = details, modifier = Modifier.pointerClick(onClick = details)) {
                            Text(stringResource(R.string.tv_discover_details))
                        }
                    }
                }
            }
        }
    }

    /** "You watched X": the strongest seed the pick was matched from, as the phone's sheet leads with. */
    @Composable
    private fun reasonLine(item: DiscoveryQueue.Item): String? {
        val seed = item.reason?.seeds?.firstOrNull() ?: return null
        return stringResource(source.seedVerb(seed.state), seed.title)
    }

    @Composable
    private fun CompleteScreen(queue: DiscoveryQueue) {
        Centered(
            stringResource(R.string.discover_complete_title),
            stringResource(
                R.string.discover_complete_body,
                queue.count(DiscoveryQueue.Action.ADDED),
                queue.count(DiscoveryQueue.Action.HIDDEN),
                queue.count(DiscoveryQueue.Action.SKIPPED),
            ),
        ) {
            val another = { lifecycleScope.launch { build(jitter = true, carrySkipped = queue.skippedIds()) }; Unit }
            Button(onClick = another, modifier = Modifier.pointerClick(onClick = another)) {
                Text(stringResource(R.string.discover_start_another))
            }
        }
    }
}

/** A title and a line beneath it in the middle of the screen, with what can be done about it. */
@Composable
private fun Centered(title: String, body: String?, actions: @Composable () -> Unit = {}) {
    val first = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (body != null) Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp).width(560.dp),
        )
        Box(Modifier.padding(top = 24.dp).focusRequester(first)) { actions() }
    }
}
