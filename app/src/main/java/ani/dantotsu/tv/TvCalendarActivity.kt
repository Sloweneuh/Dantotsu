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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Serializable
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * What airs this week, as the phone's release calendar has it: a tab a day, from yesterday to six
 * days ahead, each day's episodes as covers with their number and time. "On my list" narrows them
 * to the user's own titles.
 */
class TvCalendarActivity : AppCompatActivity(), TvScreen {

    private val days = mutableStateOf<List<Day>?>(null)
    private val listIds = mutableStateOf<Set<Int>>(emptySet())

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent {
            TvTheme {
                TvCalendar(days.value, listIds.value) { media ->
                    startActivity(Intent(this, TvMediaActivity::class.java).putExtra("media", media as Serializable))
                }
            }
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000
            val airing = runCatching { Anilist.query.recentlyUpdated(now - 86_400, now + 86_400 * 6) }.getOrNull()
            val userId = Anilist.userid
            if (userId != null) listIds.value = runCatching {
                Anilist.query.getMediaLists(anime = true, userId = userId).values.flatten().mapTo(HashSet()) { it.id }
            }.getOrDefault(emptySet())
            days.value = group(airing.orEmpty())
        }
    }

    /** By day, in order, each title once a day: "episode,airingAt" is how the query hands them. */
    private fun group(airing: List<Media>): List<Day> {
        val dayFormat = DateFormat.getDateInstance(DateFormat.FULL)
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        val today = Calendar.getInstance()
        fun dayKey(c: Calendar) = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        val byDay = sortedMapOf<Int, Pair<String, MutableList<Airing>>>()
        airing.forEach { media ->
            val (episode, at) = media.relation?.split(",")?.mapNotNull { it.trim().toLongOrNull() }
                ?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return@forEach
            val time = Calendar.getInstance().apply { timeInMillis = at * 1000 }
            val key = dayKey(time)
            val label = when (key - dayKey(today)) {
                0 -> getString(R.string.tv_today)
                -1 -> getString(R.string.tv_yesterday)
                1 -> getString(R.string.tv_tomorrow)
                else -> dayFormat.format(Date(at * 1000))
            }
            val list = byDay.getOrPut(key) { label to mutableListOf() }.second
            if (list.none { it.media.id == media.id }) {
                list += Airing(media, getString(R.string.tv_calendar_episode_at, episode.toString(), timeFormat.format(Date(at * 1000))), at)
            }
        }
        return byDay.map { (key, value) -> Day(key, value.first, value.second.sortedBy { it.at }) }
    }

    class Day(val key: Int, val label: String, val airing: List<Airing>)
    class Airing(val media: Media, val line: String, val at: Long)
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun TvCalendar(days: List<TvCalendarActivity.Day>?, listIds: Set<Int>, onOpen: (Media) -> Unit) {
    val todayKey = remember {
        Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
    }
    var picked by remember { mutableStateOf<Int?>(null) }
    var onList by remember { mutableStateOf(false) }
    val current = picked?.takeIf { key -> days?.any { it.key == key } == true }
        ?: days?.firstOrNull { it.key == todayKey }?.key ?: days?.firstOrNull()?.key
    val shown = days?.firstOrNull { it.key == current }?.airing.orEmpty()
        .filter { !onList || it.media.id in listIds }
    var focused by remember { mutableStateOf<Media?>(null) }
    val currentTab = remember { FocusRequester() }
    var focusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(current != null) {
        if (current != null && !focusPlaced) {
            androidx.compose.runtime.withFrameNanos { }
            focusPlaced = runCatching { currentTab.requestFocus() }.isSuccess
        }
    }
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(current, onList) { grid.scrollToItem(0) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(focused?.banner ?: focused?.cover, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
            Text(
                stringResource(R.string.release_calendar),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
            when {
                days == null -> CalendarNote(stringResource(R.string.loading))
                days.isEmpty() -> CalendarNote(stringResource(R.string.tv_calendar_empty))
                else -> LazyRow(
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(days, key = { it.key }) { day ->
                        val pick = { picked = day.key; focused = null }
                        androidx.tv.material3.FilterChip(
                            selected = day.key == current,
                            onClick = pick,
                            modifier = (if (day.key == current) Modifier.focusRequester(currentTab) else Modifier)
                                .pointerClick(onClick = pick),
                        ) { Text("${day.label}  ${day.airing.count { !onList || it.media.id in listIds }}") }
                    }
                    // Only signed in: there's no list to narrow to otherwise.
                    if (Anilist.userid != null) item(key = "onList") {
                        val toggle = { onList = !onList }
                        androidx.tv.material3.FilterChip(
                            selected = onList,
                            onClick = toggle,
                            modifier = Modifier.pointerClick(onClick = toggle),
                        ) { Text(stringResource(R.string.tv_calendar_on_list)) }
                    }
                }
            }
            if (days != null && days.isNotEmpty() && shown.isEmpty()) CalendarNote(stringResource(R.string.tv_calendar_day_empty))
            FocusRestingAt(24.dp) {
                LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fadingEdges(vertical = 16.dp),
                ) {
                    items(shown, key = { it.media.id }) { airing ->
                        PosterCard(
                            media = airing.media,
                            onClick = { onOpen(airing.media) },
                            onFocused = { focused = airing.media },
                            subtitle = airing.line,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp),
    )
}
