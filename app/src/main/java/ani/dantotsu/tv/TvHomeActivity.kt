package ani.dantotsu.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistHomeViewModel
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Serializable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import ani.dantotsu.media.anime.WATCHED_FRACTION
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.withContext

/**
 * The TV interface's home: the focused title shown large over its banner, and rows of the user's
 * anime beneath — what they're watching and planned, then the phone's anime tab: the seasons, AniList's
 * suggestions and charts, and the ways to explore (the discovery queue, the genres).
 */
class TvHomeActivity : AppCompatActivity(), TvScreen {

    private val home: AnilistHomeViewModel by viewModels()
    /** Trending movies, top rated, most favourite, popular: the phone anime tab's charts, by [Chart]. */
    private val charts = androidx.compose.runtime.mutableStateMapOf<Chart, List<Media>>()
    private val seasons = androidx.compose.runtime.mutableStateListOf<List<Media>?>(null, null, null)
    private val genres = androidx.compose.runtime.mutableStateListOf<Pair<String, String?>>()
    private val resumeTick = mutableIntStateOf(0)

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent {
            TvTheme {
                TvHome(
                    home = home,
                    charts = charts,
                    seasons = seasons,
                    genres = genres,
                    resumeTick = resumeTick.intValue,
                    onOpen = ::openMedia,
                    onGenre = { startActivity(Intent(this, TvBrowseActivity::class.java).putExtra(TvBrowseActivity.EXTRA_GENRE, it)) },
                    onDiscover = if (Anilist.token != null) ({ startActivity(Intent(this, TvDiscoverActivity::class.java)) }) else null,
                    onSearch = ::openSearch,
                    onSignIn = if (Anilist.token == null) ({ startActivity(Intent(this, TvSignInActivity::class.java)) }) else null,
                    onSettings = { startActivity(Intent(this, TvSettingsActivity::class.java)) },
                    onExit = if (TvMode.canExit(this)) ({ TvMode.exit(this) }) else null,
                )
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            TrackerSessions.await()
            home.initHomePageCached()
            // The account as AniList has it (name, adult setting) and, once it's known, what cloud
            // sync holds for it: the phone's home does this on launch, and TV mode never did, so a
            // TV only picked up synced settings after coming back to it from another app.
            ani.dantotsu.connections.anilist.getUserId(this@TvHomeActivity) {}
            home.initHomePage(TV_HOME_LAYOUT)
        }
        // A list change anywhere (the media page's list editor, the player marking an episode
        // watched) calls Refresh.all(): the rows are loaded again, so a title moves to where it now is.
        val refresh = androidx.lifecycle.MutableLiveData(false)
        Refresh.activity[hashCode()] = refresh
        refresh.observe(this) { changed ->
            if (!changed) return@observe
            refresh.value = false
            lifecycleScope.launch(Dispatchers.IO) { home.initHomePage(TV_HOME_LAYOUT) }
        }
        // The season and chart rows come from TvRowCache while they're a few hours old at most;
        // the account (and with it the adult setting the cache is kept by) is known first.
        lifecycleScope.launch(Dispatchers.IO) {
            TrackerSessions.await()
            loadSeasonsAndCharts()
            loadGenres()
        }
    }

    /** Previous, this and next season, each its own row, trending first — the phone's season tabs — then the charts. */
    private suspend fun loadSeasonsAndCharts() = kotlinx.coroutines.coroutineScope {
        val adultOnly = PrefManager.getVal<Boolean>(PrefName.AdultOnly)
        Anilist.currentSeasons.forEachIndexed { i, (season, year) ->
            launch {
                val found = TvRowCache.get(this@TvHomeActivity, "season_${season}_${year}_$adultOnly") {
                    runCatching {
                        Anilist.query.searchAniManga(
                            "ANIME",
                            perPage = 20,
                            sort = Anilist.sortBy[2],
                            season = season,
                            seasonYear = year,
                            adultOnly = adultOnly,
                        )?.results
                    }.getOrNull()
                }
                withContext(Dispatchers.Main) { seasons[i] = found }
            }
        }
        launch {
            // One request answers three charts, so all three are read from disk or none.
            val cached = Chart.LISTED.associateWith { TvRowCache.read(this@TvHomeActivity, it.key) }
            val rows = if (cached.values.all { it != null }) cached.mapValues { it.value!! } else {
                val list = runCatching { Anilist.query.loadAnimeList() }.getOrNull().orEmpty()
                Chart.LISTED.associateWith { chart -> list[chart.listKey!!].orEmpty() }
                    .onEach { (chart, row) -> TvRowCache.write(this@TvHomeActivity, chart.key, row) }
            }
            withContext(Dispatchers.Main) { charts.putAll(rows) }
        }
        launch {
            val popular = TvRowCache.get(this@TvHomeActivity, "${Chart.POPULAR.key}_$adultOnly") {
                runCatching {
                    Anilist.query.searchAniManga("ANIME", sort = Anilist.sortBy[1], adultOnly = adultOnly)?.results
                }.getOrNull()
            }
            withContext(Dispatchers.Main) { charts[Chart.POPULAR] = popular.orEmpty() }
        }
    }

    /**
     * The genres as tiles: their names straight away, their pictures once they arrive. Last of the
     * loads, so the rows above don't wait behind them.
     */
    private suspend fun loadGenres() {
        if (Anilist.genres == null) runCatching { Anilist.query.getGenresAndTags() }
        // Hentai only for accounts that show adult content, as AniList itself does.
        val names = Anilist.genres?.filter { Anilist.adult || !it.equals("Hentai", true) } ?: return
        withContext(Dispatchers.Main) {
            genres.clear()
            genres.addAll(names.map { it to null })
        }
        val images = runCatching { Anilist.query.getGenreThumbnails(names) }.getOrNull() ?: return
        withContext(Dispatchers.Main) {
            genres.replaceAll { (genre, image) -> genre to (images[genre] ?: image) }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onDestroy() {
        Refresh.activity.remove(hashCode())
        super.onDestroy()
    }

    private fun openSearch() {
        startActivity(Intent(this, TvSearchActivity::class.java))
    }

    /** A remote's search button (or a keyboard's) goes straight to search. */
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_SEARCH) {
            openSearch()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun openMedia(media: Media) {
        startActivity(Intent(this, TvMediaActivity::class.java).putExtra("media", media as Serializable))
    }
}

/** One row of the home: titles as posters (or, for what's being watched, as episodes), or the explore tiles. */
/** The phone anime tab's charts. [listKey] is the row in [ani.dantotsu.connections.anilist.AnilistQueries.loadAnimeList]'s answer. */
internal enum class Chart(val key: String, val listKey: String?) {
    MOVIES("trending_movies", "trendingMovies"),
    TOP_RATED("top_rated", "topRated"),
    MOST_FAV("most_fav", "mostFav"),
    POPULAR("popular", null);

    companion object {
        val LISTED = entries.filter { it.listKey != null }
    }
}

private sealed interface HomeRow {
    val title: String

    class Titles(override val title: String, val items: List<Media>, val episodes: Boolean = false) : HomeRow
    class Explore(override val title: String, val discover: Boolean, val genres: List<Pair<String, String?>>) : HomeRow
}

@Composable
private fun TvHome(
    home: AnilistHomeViewModel,
    charts: Map<Chart, List<Media>>,
    seasons: List<List<Media>?>,
    genres: List<Pair<String, String?>>,
    resumeTick: Int,
    onOpen: (Media) -> Unit,
    onGenre: (String) -> Unit,
    onDiscover: (() -> Unit)?,
    onSearch: () -> Unit,
    onSignIn: (() -> Unit)?,
    onSettings: () -> Unit,
    onExit: (() -> Unit)?,
) {
    val continuing by home.getAnimeContinue().observeAsState()
    val planned by home.getAnimePlanned().observeAsState()
    val recommended by home.getRecommendation().observeAsState()

    // The phone's anime tab, row for row, after what's the user's own.
    val seasonTitles = listOf(R.string.previous_season, R.string.this_season, R.string.next_season)
        .mapIndexed { i, title -> stringResource(title) + "  ·  " + seasonName(i) }
    val rows: List<HomeRow> = listOfNotNull(
        HomeRow.Titles(stringResource(R.string.continue_watching), continuing.orEmpty(), episodes = true),
        HomeRow.Titles(stringResource(R.string.planned_anime), planned.orEmpty()),
        HomeRow.Titles(seasonTitles[1], seasons[1].orEmpty()),
        HomeRow.Titles(stringResource(R.string.recommended), recommended.orEmpty().filter { it.anime != null }),
        HomeRow.Explore(stringResource(R.string.tv_explore), onDiscover != null, genres)
            .takeIf { onDiscover != null || genres.isNotEmpty() },
        HomeRow.Titles(seasonTitles[0], seasons[0].orEmpty()),
        HomeRow.Titles(seasonTitles[2], seasons[2].orEmpty()),
        HomeRow.Titles(stringResource(R.string.trending_movies), charts[Chart.MOVIES].orEmpty()),
        HomeRow.Titles(stringResource(R.string.popular_anime), charts[Chart.POPULAR].orEmpty()),
        HomeRow.Titles(stringResource(R.string.top_rated), charts[Chart.TOP_RATED].orEmpty()),
        HomeRow.Titles(stringResource(R.string.most_favourite), charts[Chart.MOST_FAV].orEmpty()),
    ).filter { it !is HomeRow.Titles || it.items.isNotEmpty() }
    val titleRows = rows.filterIsInstance<HomeRow.Titles>()

    var focused by remember { mutableStateOf<Media?>(null) }
    // An explore tile in focus takes the hero instead of a title: its picture, name and what it opens.
    var focusedTile by remember { mutableStateOf<Tile?>(null) }
    val discoverLabel = stringResource(R.string.discover_queue)
    val discoverLine = stringResource(R.string.tv_discover_hero)
    val genreLineFormat = stringResource(R.string.tv_genre_hero)
    val genreLine = { genre: String -> genreLineFormat.format(genre) }
    val firstCard = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The first card of the first row of titles takes focus, so the remote (and the wheel) has
    // somewhere to start from, and the list sits at its top. The rows arrive in any order — the
    // genres often before the user's own lists — and the list kept to whichever came first, so until
    // the user moves, each new top row brings it back to the start.
    var userMoved by remember { mutableStateOf(false) }
    val startId = titleRows.firstOrNull()?.items?.firstOrNull()?.id
    val topRow = rows.firstOrNull()?.title
    LaunchedEffect(startId, topRow) {
        if (startId == null || userMoved) return@LaunchedEffect
        listState.scrollToItem(0)
        androidx.compose.runtime.withFrameNanos { }
        runCatching { firstCard.requestFocus() }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val peekPx = with(androidx.compose.ui.platform.LocalDensity.current) { ROW_PEEK.roundToPx() }
    // Each row's way in, for stepping to it: entered through its focus restorer.
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    val placeFocus = {
        // The wheel turned with nothing in focus: it starts from the first card, as the remote does.
        runCatching { firstCard.requestFocus() }
        Unit
    }
    // The focused title as the rows have it now: after a reload the hero shows its new list status,
    // not the one it had when it took focus.
    val shown = focused?.let { f -> titleRows.firstNotNullOfOrNull { row -> row.items.firstOrNull { it.id == f.id } } ?: f }
        ?: titleRows.firstOrNull()?.items?.firstOrNull()

    // The screen fades in once the user's own rows are in (or there's no account to load them for)
    // — or after a few seconds, with whatever has come — over the wordmark the launch window showed:
    // drawn as each row arrived, the screen filled in piece by piece, rows pushing each other down.
    var ready by remember { mutableStateOf(false) }
    val ownRowsIn = Anilist.token == null || continuing != null || planned != null
    LaunchedEffect(ownRowsIn, titleRows.isNotEmpty()) { if (ownRowsIn && titleRows.isNotEmpty()) ready = true }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(READY_TIMEOUT_MS)
        ready = true
    }
    val contentAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (ready) 1f else 0f, androidx.compose.animation.core.tween(FADE_IN_MS), label = "homeFadeIn",
    )
    Box(Modifier.fillMaxSize().background(Color.Black)) {
    if (contentAlpha < 1f) TvLaunchLogo(Modifier.align(Alignment.Center).graphicsLayer { alpha = 1f - contentAlpha })
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha }.background(MaterialTheme.colorScheme.background)) {
        Backdrop(
            imageUrl = focusedTile?.let { it.image } ?: shown?.banner ?: shown?.cover,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.66f),
        )
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                if (onSignIn != null) {
                    Button(onClick = onSignIn, modifier = Modifier.pointerClick(onClick = onSignIn)) {
                        Text(stringResource(R.string.tv_sign_in))
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Button(onClick = onSearch, colors = ButtonDefaults.colors(), modifier = Modifier.pointerClick(onClick = onSearch)) {
                    Text(stringResource(R.string.search))
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onSettings, colors = ButtonDefaults.colors(), modifier = Modifier.pointerClick(onClick = onSettings)) {
                    Text(stringResource(R.string.settings))
                }
                // Not on a TV, where there's no other interface to leave for.
                if (onExit != null) {
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onExit, colors = ButtonDefaults.colors(), modifier = Modifier.pointerClick(onClick = onExit)) {
                        Text(stringResource(R.string.tv_exit))
                    }
                }
            }
            val heroModifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 20.dp).height(170.dp)
            focusedTile?.let { TileHero(it, heroModifier) } ?: Hero(shown, heroModifier)
            if (titleRows.isEmpty()) {
                Text(
                    if (Anilist.token == null) stringResource(R.string.tv_signed_out) else "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(48.dp),
                )
            }
            // A focused card rests below its row's title (its growth inside the row's padding) with
            // the end of the row before it peeking out above — as much of it everywhere, rows the list
            // can scroll fully into place and the last ones it can't alike.
            val density = androidx.compose.ui.platform.LocalDensity.current
            var rowLead by remember { mutableStateOf(56.dp) }
            // The rows away from focus step back, smaller and dimmed: still there to see, the focused
            // one plainly the one in use.
            var focusedRow by remember { mutableIntStateOf(0) }
            // No fade at the top until there's a row up there to fade: it would dim the first one's title.
            val topFade by androidx.compose.animation.core.animateDpAsState(
                if (listState.canScrollBackward) EDGE_FADE else 0.dp, label = "topFade",
            )
            FocusRestingAt(rowLead + ROW_PEEK) { LazyColumn(
                state = listState,
                modifier = Modifier.fadingEdges(vertical = EDGE_FADE, top = topFade).stepsFocus(onNoFocus = placeFocus, onMoved = { direction ->
                    // The wheel's step brings the new row to where the remote's would rest it, the
                    // end of the row above peeking out; the focus move alone left the list where it was.
                    if (direction == androidx.compose.ui.focus.FocusDirection.Down || direction == androidx.compose.ui.focus.FocusDirection.Up) {
                        val target = focusedRow
                        scope.launch { listState.animateScrollToItem(target, -peekPx) }
                    }
                }, stepRow = { down ->
                    // One row on, scrolled to where the remote rests it, then focus into it: on the
                    // card it was left on, or its first.
                    val target = (focusedRow + if (down) 1 else -1).coerceIn(0, rows.lastIndex)
                    focusedRow = target
                    userMoved = true
                    scope.launch {
                        listState.animateScrollToItem(target, -peekPx)
                        runCatching { rowFocus[rows[target].title]?.requestFocus() }
                    }
                }),
                userScrollEnabled = false,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                rows.forEachIndexed { rowIndex, row ->
                    item(key = row.title) {
                        val inUse = rowIndex == focusedRow
                        val rowAlpha by androidx.compose.animation.core.animateFloatAsState(
                            if (inUse) 1f else AWAY_ROW_ALPHA, label = "rowAlpha",
                        )
                        val rowScale by androidx.compose.animation.core.animateFloatAsState(
                            if (inUse) 1f else AWAY_ROW_SCALE, label = "rowScale",
                        )
                        Column(Modifier.graphicsLayer {
                            alpha = rowAlpha
                            scaleX = rowScale
                            scaleY = rowScale
                            // Shrunk towards the focused row, from the start edge the rows line up on, and
                            // drawn in closer to it than the spacing laid out for full-size rows.
                            val above = rowIndex < focusedRow
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, if (above) 1f else 0f)
                            translationY = (1f - rowScale) / (1f - AWAY_ROW_SCALE) * AWAY_ROW_PULL.toPx() * (if (above) 1f else -1f)
                        }) {
                            Text(
                                row.title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier
                                    .padding(start = 48.dp, bottom = ROW_TITLE_GAP)
                                    .onSizeChanged { rowLead = with(density) { it.height.toDp() } + ROW_TITLE_GAP + ROW_PADDING },
                            )
                            // Up or down into a row lands on the card it was left on, or its first: the
                            // card that happened to sit above or below (the wide tiles line up with no
                            // poster in particular) was rarely the one meant.
                            val rowStart = remember { FocusRequester() }
                            val rowEntry = remember { FocusRequester() }.also { rowFocus[row.title] = it }
                            // ...and at the row's margin, so the first card comes back to where it began.
                            FocusRestingAt(48.dp) { LazyRow(
                                modifier = Modifier.fadingEdges(horizontal = EDGE_FADE).focusRequester(rowEntry).focusRestorer(rowStart),
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = ROW_PADDING),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                when (row) {
                                    is HomeRow.Titles -> items(row.items, key = { it.id }) { media ->
                                        val modifier = if (media === row.items.first()) {
                                            Modifier.focusRequester(rowStart).then(if (row === titleRows.first()) Modifier.focusRequester(firstCard) else Modifier)
                                        } else Modifier
                                        if (row.episodes) ContinueCard(
                                            media = media,
                                            resumeTick = resumeTick,
                                            onClick = { onOpen(media) },
                                            onFocused = { focused = media; focusedTile = null; focusedRow = rowIndex; if (media.id != startId) userMoved = true },
                                            modifier = modifier,
                                        ) else PosterCard(
                                            media = media,
                                            onClick = { onOpen(media) },
                                            onFocused = { focused = media; focusedTile = null; focusedRow = rowIndex; if (media.id != startId) userMoved = true },
                                            subtitle = progressLabel(media),
                                            modifier = modifier,
                                        )
                                    }
                                    is HomeRow.Explore -> {
                                        if (row.discover && onDiscover != null) item(key = "discover") {
                                            ExploreTile(
                                                label = stringResource(R.string.discover_queue),
                                                image = DISCOVER_IMAGE,
                                                onClick = onDiscover,
                                                onFocused = { focusedTile = Tile(discoverLabel, DISCOVER_IMAGE, discoverLine); focusedRow = rowIndex; userMoved = true },
                                                modifier = Modifier.focusRequester(rowStart),
                                            )
                                        }
                                        items(row.genres, key = { it.first }) { (genre, image) ->
                                            ExploreTile(
                                                label = genre,
                                                image = image,
                                                onClick = { onGenre(genre) },
                                                onFocused = { focusedTile = Tile(genre, image, genreLine(genre)); focusedRow = rowIndex; userMoved = true },
                                                modifier = if (!row.discover && genre == row.genres.first().first) Modifier.focusRequester(rowStart) else Modifier,
                                            )
                                        }
                                    }
                                }
                            } }
                        }
                    }
                }
            } }
        }
    }
    }
}

/**
 * The rows TV mode asks the home request for, whatever the phone's home layout has switched on (it
 * comes over with cloud sync, and once left the TV with none of its own rows): Continue Watching,
 * Planned anime and the recommendations — no manga, which TV mode doesn't show.
 * Indices as [ani.dantotsu.connections.anilist.AnilistQueries.initHomePage] reads them.
 */
private val TV_HOME_LAYOUT = listOf(true, false, true, false, false, false, false, true, false)

private const val READY_TIMEOUT_MS = 4_000L
private const val FADE_IN_MS = 450

/** The wordmark, where and as large as the launch window (tv_launch_window) draws it. */
@Composable
private fun TvLaunchLogo(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(R.drawable.ic_dantotsu_logo),
        contentDescription = null,
        modifier = modifier.width(300.dp).height(83.dp),
    )
}

private val ROW_TITLE_GAP = 12.dp
private val ROW_PADDING = 12.dp
private val ROW_PEEK = 44.dp
private const val AWAY_ROW_ALPHA = 0.4f
private const val AWAY_ROW_SCALE = 0.9f
private val AWAY_ROW_PULL = 18.dp
/** Inside the rows' 48dp margin, so a card at rest by the edge is never faded. */
private val EDGE_FADE = 32.dp

/** The banner the phone's anime tab puts behind its discovery queue button. */
private const val DISCOVER_IMAGE = "https://s4.anilist.co/file/anilistcdn/media/anime/banner/21-wf37VakJmZqs.jpg"

private val WIDE_CARD_WIDTH = 240.dp

/** "Fall 2026": AniList's season [index] (previous, current, next) in words. */
@Composable
private fun seasonName(index: Int): String {
    val (season, year) = Anilist.currentSeasons[index]
    val name = when (season) {
        "WINTER" -> R.string.tv_season_WINTER
        "SPRING" -> R.string.tv_season_SPRING
        "SUMMER" -> R.string.tv_season_SUMMER
        else -> R.string.tv_season_FALL
    }
    return stringResource(name, year)
}

/**
 * A title being watched, as the episode it's at: the episode's own picture with how far into it the
 * user got, when it was left part-way on this device; otherwise the next one, over the series banner.
 * [resumeTick] changes each time home comes back to the front, so a position just saved shows.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ContinueCard(media: Media, resumeTick: Int, onClick: () -> Unit, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    val left = remember(media.id, media.userProgress, resumeTick) { leftPartWay(media) }
    val episode = left?.episode ?: ((media.userProgress ?: 0) + 1).toString()
    Column(modifier.width(WIDE_CARD_WIDTH)) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .pointerClick(onClick = onClick),
            shape = CardDefaults.shape(CARD_SHAPE),
            scale = CardDefaults.scale(focusedScale = FOCUSED_SCALE),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(FOCUS_OUTLINE, MaterialTheme.colorScheme.primary), shape = CARD_SHAPE),
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                GlideImage(
                    model = left?.thumb ?: media.banner ?: media.cover,
                    contentDescription = media.userPreferredName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (left != null) Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(left.fraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
        Text(
            media.userPreferredName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            if (left != null) stringResource(R.string.tv_resume_episode, episode)
            else stringResource(R.string.tv_episode_n, episode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/** An episode the player left part-way: which, how far in, and its picture when it had one. */
private class PartWay(val episode: String, val fraction: Float, val thumb: String?)

private fun leftPartWay(media: Media): PartWay? {
    val episode = PrefManager.getNullableCustomVal("${media.id}_current_ep", null, String::class.java) ?: return null
    // An episode the list already counts as watched is behind the user, whatever was saved for it.
    if ((episode.toFloatOrNull() ?: return null) <= (media.userProgress ?: 0)) return null
    val position = PrefManager.getNullableCustomVal("${media.id}_$episode", null, Long::class.java) ?: return null
    val length = PrefManager.getNullableCustomVal("${media.id}_${episode}_max", null, Long::class.java)
        ?.takeIf { it > 0 } ?: return null
    val fraction = position.toFloat() / length
    if (fraction <= 0f || fraction >= WATCHED_FRACTION) return null
    return PartWay(episode, fraction, PrefManager.getNullableCustomVal("${media.id}_current_thumb", null, String::class.java))
}

/** A way into more anime (the discovery queue, or a genre) as a wide picture with its name across it. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ExploreTile(label: String, image: String?, onClick: () -> Unit, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier
            .width(WIDE_CARD_WIDTH)
            .aspectRatio(16f / 9f)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .pointerClick(onClick = onClick),
        shape = CardDefaults.shape(CARD_SHAPE),
        scale = CardDefaults.scale(focusedScale = FOCUSED_SCALE),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(FOCUS_OUTLINE, MaterialTheme.colorScheme.primary), shape = CARD_SHAPE),
        ),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (image != null) GlideImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            Text(
                label,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
            )
        }
    }
}

/** An explore tile, as the hero shows it. */
private data class Tile(val label: String, val image: String?, val line: String)

@Composable
private fun TileHero(tile: Tile, modifier: Modifier = Modifier) {
    Column(modifier.width(520.dp), verticalArrangement = Arrangement.Bottom) {
        FittedTitle(tile.label)
        Text(
            tile.line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** The focused title, large: name, what it is, and the start of its synopsis. */
@Composable
private fun Hero(media: Media?, modifier: Modifier = Modifier) {
    Column(modifier.width(520.dp), verticalArrangement = Arrangement.Bottom) {
        if (media == null) return@Column
        FittedTitle(media.userPreferredName)
        Text(
            metaLine(media),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        ListStatusPill(media, Modifier.padding(top = 8.dp))
        Text(
            plainDescription(media),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
