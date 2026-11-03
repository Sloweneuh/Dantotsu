package ani.dantotsu.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.initActivity
import ani.dantotsu.notifications.NotificationReadState
import ani.dantotsu.notifications.unread.UnreadChapterStore
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.text.DateFormat
import java.util.Date

/** The new-episode announcements this device stored, newest first: on a TV, nothing else shows them. */
internal fun storedEpisodes(): List<UnreadChapterStore> =
    PrefManager.getNullableVal<List<UnreadChapterStore>>(PrefName.UnreadChapterNotificationStore, null)
        .orEmpty().filter { it.type == "UnreadEpisode" }.sortedByDescending { it.time }

/** How many of [storedEpisodes] haven't been opened. */
internal fun unreadEpisodeCount(): Int {
    val unread = NotificationReadState.unreadKeys()
    return storedEpisodes().count { NotificationReadState.keyOf(it) in unread }
}

/**
 * New episodes, as the phone's notifications list them: a TV has no notification shade to look at.
 * Each opens its title (marking it seen); "Mark all as seen" clears them.
 */
class TvInboxActivity : AppCompatActivity(), TvScreen {

    private val entries = mutableStateOf(storedEpisodes())
    private val unread = mutableStateOf(NotificationReadState.unreadKeys())

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent {
            TvTheme {
                TvInbox(
                    entries = entries.value,
                    unread = unread.value,
                    onOpen = ::open,
                    onMarkAll = {
                        NotificationReadState.markRead(entries.value.map { NotificationReadState.keyOf(it) })
                        unread.value = NotificationReadState.unreadKeys()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        entries.value = storedEpisodes()
        unread.value = NotificationReadState.unreadKeys()
    }

    private fun open(entry: UnreadChapterStore) {
        NotificationReadState.markRead(NotificationReadState.keyOf(entry))
        unread.value = NotificationReadState.unreadKeys()
        lifecycleScope.launch {
            val media = withContext(Dispatchers.IO) {
                if (Anilist.token == null) Anilist.getSavedToken()
                runCatching { Anilist.query.getMedia(entry.mediaId) }.getOrNull()
            }
            if (media == null) {
                snackString(getString(R.string.handoff_media_not_found))
                return@launch
            }
            startActivity(Intent(this@TvInboxActivity, TvMediaActivity::class.java).putExtra("media", media as Serializable))
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun TvInbox(
    entries: List<UnreadChapterStore>,
    unread: Set<String>,
    onOpen: (UnreadChapterStore) -> Unit,
    onMarkAll: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(entries.isNotEmpty()) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tv_new_episodes), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                if (entries.any { NotificationReadState.keyOf(it) in unread }) {
                    OutlinedButton(onClick = onMarkAll, modifier = Modifier.pointerClick(onClick = onMarkAll)) {
                        Text(stringResource(R.string.tv_mark_all_seen))
                    }
                }
            }
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.tv_no_new_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            FocusRestingAt(24.dp) {
                LazyColumn(
                    // Room for the focus outline at the sides.
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fadingEdges(vertical = 16.dp),
                ) {
                    items(entries, key = { "${it.mediaId}:${it.lastChapter}:${it.time}" }) { entry ->
                        val isUnread = NotificationReadState.keyOf(entry) in unread
                        val open = { onOpen(entry) }
                        // A card of its own rather than a list item: its leading slot cut the
                        // cover to a sliver, and it filled the row edge to edge, square.
                        androidx.tv.material3.Card(
                            onClick = open,
                            modifier = (if (entry == entries.first()) Modifier.focusRequester(first) else Modifier)
                                .fillMaxWidth()
                                .pointerClick(onClick = open),
                            shape = androidx.tv.material3.CardDefaults.shape(CARD_SHAPE),
                            // Not grown when focused: a row this wide grew past the list's edge
                            // and was cut there. The outline says where focus is.
                            scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1f),
                            colors = androidx.tv.material3.CardDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                            border = androidx.tv.material3.CardDefaults.border(
                                focusedBorder = androidx.tv.material3.Border(
                                    androidx.compose.foundation.BorderStroke(FOCUS_OUTLINE, MaterialTheme.colorScheme.primary), shape = CARD_SHAPE,
                                ),
                            ),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                GlideImage(
                                    model = entry.image,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(56.dp).height(80.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                                )
                                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                    Text(entry.mediaName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val line = MaterialTheme.typography.bodyMedium
                                        val tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        Text(stringResource(R.string.tv_episode_n, entry.lastChapter.toString()), style = line, color = tint)
                                        // The release language with its flag, as the media page shows it.
                                        entry.language?.let { language ->
                                            Text("  ·  ", style = line, color = tint)
                                            // Sub or dub, at the text's size.
                                            val option = ani.dantotsu.connections.malsync.LanguageMapper.mapLanguage(language)
                                            androidx.tv.material3.Icon(
                                                androidx.compose.ui.res.painterResource(option.iconRes),
                                                contentDescription = null,
                                                tint = tint,
                                                modifier = Modifier.padding(end = 4.dp).size(16.dp),
                                            )
                                            Text(ani.dantotsu.connections.malsync.LanguageMapper.shortCode(option.id), style = line, color = tint)
                                        }
                                        Text("  ·  " + dateFormat.format(Date(entry.time)), style = line, color = tint)
                                    }
                                }
                                if (isUnread) Box(Modifier.padding(horizontal = 8.dp).size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                            }
                        }
                    }
                }
            }
        }
    }
}
