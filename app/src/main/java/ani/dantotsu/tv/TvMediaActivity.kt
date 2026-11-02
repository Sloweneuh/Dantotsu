package ani.dantotsu.tv

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.deleteFromList
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.others.getSerialized
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.themes.ThemeManager
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A title on the TV: its banner and details, one button to start or continue, the source the
 * episodes come from, and the episodes themselves. Playing goes through the same server picker and
 * player as the phone, which read this screen's [MediaDetailsViewModel] just as they read the media
 * page's.
 */
class TvMediaActivity : AppCompatActivity(), TvScreen {

    private val model: MediaDetailsViewModel by viewModels()

    /** The source episodes are being shown from, as an index into [ani.dantotsu.parsers.WatchSources]. */
    private val sourceIndex = mutableIntStateOf(0)
    private var episodesRequested = false

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        val start: Media = intent.getSerialized("media") ?: return finish()
        model.watchSources = if (start.isAdult) HAnimeSources else AnimeSources

        // Details arrive from AniList; the episode search starts once, on the full entry.
        model.getMedia().observe(this) { media ->
            if (media == null) return@observe
            favourite.value = media.isFav
            // Opened from the downloads screen: on the downloaded episodes, the first time round.
            media.selected = model.loadSelected(media, !episodesRequested && intent.getBooleanExtra(EXTRA_DOWNLOADS, false))
            sourceIndex.intValue = media.selected!!.sourceIndex
            if (!episodesRequested) {
                episodesRequested = true
                loadEpisodes(media)
                loadMalSync(media)
            }
        }
        lifecycleScope.launch(Dispatchers.IO) { model.loadMedia(start) }

        setContent {
            TvTheme {
                // The view model's media starts out null (not unset), so the fallback is ours to make.
                val media = model.getMedia().observeAsState().value ?: start
                val loaded by model.getEpisodes().observeAsState()
                val episodes = loaded?.get(sourceIndex.intValue)
                // The player and its server picker look episodes up on the media itself.
                LaunchedEffect(episodes, media) { media.anime?.episodes = episodes }
                // This title's downloads under way, by episode; one leaving means it finished or
                // stopped, so what's on disk is looked at again.
                val tracked by ani.dantotsu.download.DownloadTracker.items.collectAsState()
                val active = tracked.filter { it.type == ani.dantotsu.media.MediaType.ANIME && it.mediaId == media.id }
                    .associateBy { it.label }
                LaunchedEffect(active.keys) { downloadsVersion.intValue++ }
                val version = downloadsVersion.intValue
                val downloadState: (Episode) -> EpisodeDownload = { episode ->
                    val item = active[episode.number]
                    when {
                        item?.state == ani.dantotsu.download.DownloadState.DOWNLOADING -> EpisodeDownload.Running(item.percent)
                        item?.state == ani.dantotsu.download.DownloadState.QUEUED -> EpisodeDownload.Queued
                        version >= 0 && isDownloaded(media, episode) -> EpisodeDownload.Done
                        else -> EpisodeDownload.None
                    }
                }
                TvMedia(
                    media = media,
                    sourceNames = sourcesVersion.intValue.let { model.watchSources?.names.orEmpty() },
                    sourceIndex = sourceIndex.intValue,
                    sourceIcon = ::sourceIcon,
                    isDownloads = ::isDownloads,
                    onAddExtensions = { startActivity(android.content.Intent(this, TvExtensionsActivity::class.java)) },
                    onOpenMedia = { related ->
                        startActivity(android.content.Intent(this, TvMediaActivity::class.java).putExtra("media", related as java.io.Serializable))
                    },
                    onSourceSettings = sourceSettingsId(sourceIndex.intValue)?.let { id ->
                        {
                            startActivity(
                                android.content.Intent(this, TvExtensionSettingsActivity::class.java)
                                    .putExtra(TvExtensionSettingsActivity.EXTRA_SOURCE, id)
                            )
                        }
                    },
                    episodes = episodes?.values?.sortedBy { it.number.toFloatOrNull() ?: Float.MAX_VALUE },
                    onPlay = { play(media, it) },
                    downloadState = downloadState,
                    onDownload = { download(media, listOf(it.number)) },
                    onDownloadMany = { download(media, it) },
                    onCancelDownload = { cancelDownload(media, it, active[it.number]) },
                    onDeleteDownload = { deleteDownload(media, it) },
                    onSource = { changeSource(media, it) },
                    matchedName = matchedName.value,
                    searchSource = ::searchSource,
                    onPickMatch = { pickMatch(media, it) },
                    listVersion = listVersion.intValue,
                    onSaveList = { status, progress -> saveList(media, status, progress) },
                    onRemoveFromList = { removeFromList(media) },
                    malSync = malSync.value,
                    loadMalSyncLanguages = { malSyncLanguages(media) },
                    onMalSyncLanguage = { pickMalSyncLanguage(media, it) },
                    favourite = favourite.value,
                    onFavourite = if (ani.dantotsu.connections.anilist.Anilist.userid != null) ({ toggleFavourite(media) }) else null,
                )
                setup.value?.let { kind ->
                    DownloadSetupDialog(
                        setup = kind,
                        canPickFolder = remember { ani.dantotsu.util.StoragePermissions.canPickFolder(this) },
                        onAppStorage = {
                            setup.value = null
                            if (ani.dantotsu.util.StoragePermissions.useAppStorage(this)) afterSetup?.invoke()
                            else ani.dantotsu.snackString(getString(R.string.dir_error))
                        },
                        onPickFolder = {
                            setup.value = null
                            folderPicker.registerForCallback { ok -> if (ok) afterSetup?.invoke() }
                            runCatching { folderPicker.launch() }.onFailure { ani.dantotsu.snackString(getString(R.string.dir_error)) }
                        },
                        onInstallAddon = { setup.value = null; installDownloadAddon() },
                        onDismiss = { setup.value = null },
                    )
                }
            }
        }
    }

    companion object {
        /** Open on the title's downloaded episodes rather than its usual source. */
        const val EXTRA_DOWNLOADS = "downloads"
    }

    /** Whether the title is one of the user's favourites on AniList, as the heart shows it. */
    private val favourite = mutableStateOf(false)

    /**
     * Favourites or unfavourites the title on AniList, as the phone's heart does. The heart turns
     * at once and back if AniList refuses, rather than waiting on the round trip.
     */
    private fun toggleFavourite(media: Media) {
        val wanted = !favourite.value
        favourite.value = wanted
        media.isFav = wanted
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                ani.dantotsu.connections.anilist.Anilist.mutation.toggleFav(
                    ani.dantotsu.connections.anilist.AnilistMutations.FavType.ANIME, media.id,
                )
            }.getOrDefault(false)
            if (ok) {
                ani.dantotsu.Refresh.all()
                ani.dantotsu.snackString(getString(if (wanted) R.string.tv_favourite_added else R.string.tv_favourite_removed))
            } else {
                favourite.value = !wanted
                media.isFav = !wanted
                ani.dantotsu.snackString(getString(R.string.list_update_failed))
            }
        }
    }

    /** MALSync's newest episode in the title's release language, once it's answered. */
    private val malSync = mutableStateOf<TvMalSync?>(null)

    private fun malSyncOn() = ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.MalSyncInfoEnabled) &&
        ani.dantotsu.settings.saving.PrefManager.getVal<String>(ani.dantotsu.settings.saving.PrefName.MalSyncCheckMode) != "manga"

    /**
     * The newest episode, asked of MALSync on the phone's media page's terms: switched on, online, a
     * MAL id to ask with, and not finished by the user. A source the user chose to
     * ignore for this title is left out, as there.
     */
    private fun loadMalSync(media: Media) {
        if (!malSyncOn() || !ani.dantotsu.isOnline(this)) return
        val malId = media.idMAL
        if (malId != null && media.userStatus != "COMPLETED") lifecycleScope.launch(Dispatchers.IO) {
            val language = ani.dantotsu.connections.malsync.MalSyncLanguageHelper.getPreferredLanguage(media.id)
            val result = runCatching { ani.dantotsu.connections.malsync.MalSyncApi.getLastEpisode(media.id, malId, language) }.getOrNull()
            val last = result?.lastEp ?: return@launch
            val ignores = ani.dantotsu.connections.ReleaseSourceIgnores
            ani.dantotsu.connections.ReleaseSourceAnswers.record(
                ani.dantotsu.connections.ReleaseSourceIgnores.Provider.MALSYNC, ignores.anilist(media.id), last.total, result.source,
            )
            if (ignores.isIgnored(ani.dantotsu.connections.ReleaseSourceIgnores.Provider.MALSYNC, ignores.anilist(media.id))) return@launch
            val total = media.anime?.totalEpisodes
            // The phone's rule: only a number short of the whole, and not behind the user.
            val shown = when {
                last.total < (media.userProgress ?: 0) -> false
                total != null -> last.total < total
                else -> true
            }
            malSync.value = TvMalSync(last.total, ani.dantotsu.connections.malsync.LanguageMapper.mapLanguage(result.id), shown)
        }
    }

    /** The release languages MALSync has for the title, dubs in English first as on the phone. */
    private suspend fun malSyncLanguages(media: Media): List<ani.dantotsu.connections.malsync.LanguageOption> = withContext(Dispatchers.IO) {
        val malId = media.idMAL ?: return@withContext emptyList()
        runCatching {
            ani.dantotsu.connections.malsync.LanguageMapper.mapLanguagesWithEpisodes(
                ani.dantotsu.connections.malsync.MalSyncApi.getAvailableLanguagesWithEpisodes(malId)
            ).sortedBy { when (it.id) { "en/dub" -> 0; "en/sub" -> 1; else -> 2 } }
        }.getOrDefault(emptyList())
    }

    private fun pickMalSyncLanguage(media: Media, option: ani.dantotsu.connections.malsync.LanguageOption) {
        ani.dantotsu.connections.malsync.MalSyncLanguageHelper.setPreferredLanguage(media.id, option.id)
        loadMalSync(media)
    }

    private val folderPicker = ani.dantotsu.util.LauncherWrapper(
        this, androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    )
    private val downloads by lazy { Injekt.get<ani.dantotsu.download.DownloadsManager>() }

    /** Bumped when an episode's download may have come or gone, so the cards look at the disk again. */
    private val downloadsVersion = mutableIntStateOf(0)

    /** What a first download is waiting on, asked in a dialog, and the download to go on with after. */
    private val setup = mutableStateOf<DownloadSetup?>(null)
    private var afterSetup: (() -> Unit)? = null

    private fun isDownloaded(media: Media, episode: Episode) =
        downloads.queryDownload(media.mainName(), episode.number, ani.dantotsu.media.MediaType.ANIME)

    /**
     * Downloads episodes through the same server sheet as playing one (several: the server picked
     * for the first, used for the rest, as the phone does), once there's somewhere to
     * keep it and the add-on to make it (an external download manager, if one is set, needs neither).
     */
    private fun download(media: Media, numbers: List<String>) {
        if (numbers.isEmpty()) return
        val external = ani.dantotsu.settings.saving.PrefManager.getVal<Int>(ani.dantotsu.settings.saving.PrefName.DownloadManager) != 0
        if (!external) {
            afterSetup = { download(media, numbers) }
            if (!ani.dantotsu.util.StoragePermissions.hasDirAccess(this)) {
                setup.value = DownloadSetup.STORAGE
                return
            }
            if (!Injekt.get<ani.dantotsu.addons.download.DownloadAddonManager>().isAvailable()) {
                setup.value = DownloadSetup.ADDON
                return
            }
        }
        afterSetup = null
        media.selected?.let { model.saveSelected(media.id, it) }
        model.onEpisodeClick(
            media, numbers.first(), supportFragmentManager,
            isDownload = true, episodes = ArrayList(numbers),
        )
    }

    private fun installDownloadAddon() {
        val manager = Injekt.get<ani.dantotsu.addons.download.DownloadAddonManager>()
        ani.dantotsu.snackString(getString(R.string.tv_download_addon_installing))
        lifecycleScope.launch(Dispatchers.IO) {
            ani.dantotsu.addons.AddonDownloader.update(
                activity = this@TvMediaActivity,
                manager,
                repo = ani.dantotsu.addons.download.DownloadAddonManager.REPO,
                currentVersion = manager.getVersion() ?: "",
            )
        }
    }

    /** Stops an episode's download, queued or running, and drops what it had written, as the phone does. */
    private fun cancelDownload(media: Media, episode: Episode, item: ani.dantotsu.download.DownloadItem?) {
        if (item != null) ani.dantotsu.download.DownloadTracker.cancel(this, item)
        else sendBroadcast(
            android.content.Intent(ani.dantotsu.download.anime.AnimeDownloaderService.ACTION_CANCEL_DOWNLOAD).putExtra(
                ani.dantotsu.download.anime.AnimeDownloaderService.EXTRA_TASK_NAME,
                ani.dantotsu.download.anime.AnimeDownloaderService.AnimeDownloadTask.getTaskName(media.mainName(), episode.number),
            )
        )
        downloads.removeDownload(
            ani.dantotsu.download.DownloadedType(media.mainName(), episode.number, ani.dantotsu.media.MediaType.ANIME),
            toast = false,
        ) { downloadsVersion.intValue++ }
    }

    private fun deleteDownload(media: Media, episode: Episode) {
        downloads.removeDownload(
            ani.dantotsu.download.DownloadedType(media.mainName(), episode.number, ani.dantotsu.media.MediaType.ANIME),
        ) {
            val taskName = ani.dantotsu.download.anime.AnimeDownloaderService.AnimeDownloadTask.getTaskName(media.mainName(), episode.number)
            ani.dantotsu.settings.saving.PrefManager.getAnimeDownloadPreferences().edit().remove(taskName).apply()
            downloadsVersion.intValue++
        }
    }

    /** The source entry the episodes come from, as the source names it — what "Wrong title?" fixes. */
    private val matchedName = mutableStateOf<String?>(null)

    private fun loadEpisodes(media: Media) {
        lifecycleScope.launch(Dispatchers.IO) {
            model.loadEpisodes(media, sourceIndex.intValue)
            refreshMatchedName(media)
        }
    }

    private suspend fun refreshMatchedName(media: Media) {
        matchedName.value = runCatching {
            model.watchSources?.get(sourceIndex.intValue)?.loadSavedShowResponse(media.id)?.name
        }.getOrNull()
    }

    private suspend fun searchSource(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        model.watchSources?.get(sourceIndex.intValue)?.search(query).orEmpty()
    }

    /** Bumped when the user's list entry changes here, so what shows it is drawn again. */
    private val listVersion = mutableIntStateOf(0)

    /**
     * Saves the entry from the TV's list editor — through the same path as the phone's sheet, so
     * AniList and the trackers mirroring it all get it. Score, notes and dates are kept as they were.
     */
    private fun saveList(media: Media, status: String, progress: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val score = media.userScore.takeIf { it > 0 }
            val started = if (status == "CURRENT" && media.userStartedAt.isEmpty())
                ani.dantotsu.connections.anilist.api.FuzzyDate().getToday() else media.userStartedAt
            val ok = runCatching {
                ani.dantotsu.media.ListEntrySave.save(
                    media, progress, media.userVolume, score, media.userRepeat, media.notes,
                    status, started, media.userCompletedAt, score,
                )
            }.getOrDefault(false)
            if (ok) {
                media.userStatus = status
                media.userProgress = progress
                media.userStartedAt = started
                ani.dantotsu.Refresh.all()
            }
            listVersion.intValue++
            ani.dantotsu.snackString(getString(if (ok) R.string.list_updated else R.string.list_update_failed))
        }
    }

    private fun removeFromList(media: Media) {
        media.deleteFromList(lifecycleScope, onSuccess = {
            media.userStatus = null
            media.userProgress = null
            media.userListId = null
            ani.dantotsu.Refresh.all()
            listVersion.intValue++
            ani.dantotsu.snackString(getString(R.string.deleted_from_list))
        }, onError = {
            ani.dantotsu.snackString(getString(R.string.list_update_failed))
        }, onNotFound = {
            ani.dantotsu.snackString(getString(R.string.list_update_failed))
        })
    }

    private fun isDownloads(index: Int) =
        runCatching { model.watchSources?.get(index) }.getOrNull() is ani.dantotsu.parsers.OfflineAnimeParser

    /** Bumped on coming back to the page: extensions added from the picker's last row show up in it. */
    private val sourcesVersion = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onRestart() {
        super.onRestart()
        sourcesVersion.intValue++
    }

    private fun extensionOf(index: Int) =
        (runCatching { model.watchSources?.get(index) }.getOrNull() as? ani.dantotsu.parsers.DynamicAnimeParser)?.extension

    /** A source's extension icon, for its button and its row in the picker. */
    private fun sourceIcon(index: Int): androidx.compose.ui.graphics.ImageBitmap? =
        runCatching { extensionOf(index)?.icon?.toBitmap(64, 64)?.asImageBitmap() }.getOrNull()

    /**
     * The source whose settings the media page's settings button opens: the one in use (an
     * extension carries one a language), or else its first with settings; none when it has none.
     */
    private fun sourceSettingsId(index: Int): Long? {
        val parser = runCatching { model.watchSources?.get(index) }.getOrNull() as? ani.dantotsu.parsers.DynamicAnimeParser ?: return null
        val sources = parser.extension.sources
        val active = sources.getOrNull(parser.sourceLanguage) as? eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
        return (active ?: sources.filterIsInstance<eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource>().firstOrNull())?.id
    }

    /** The user's pick from "Wrong title?": remembered for this media, and its episodes loaded. */
    private fun pickMatch(media: Media, response: ShowResponse) {
        matchedName.value = response.name
        lifecycleScope.launch(Dispatchers.IO) {
            model.overrideEpisodes(sourceIndex.intValue, response, media.id)
        }
    }

    private fun changeSource(media: Media, index: Int) {
        if (index == sourceIndex.intValue) return
        sourceIndex.intValue = index
        media.selected = (media.selected ?: model.loadSelected(media)).also { it.sourceIndex = index }
        model.saveSelected(media.id, media.selected!!)
        model.saveSelectedSourceName(media.id, model.watchSources?.names?.getOrNull(index))
        loadEpisodes(media)
    }

    private fun play(media: Media, episode: Episode) {
        model.continueMedia = false
        media.selected?.let { model.saveSelected(media.id, it) }
        model.onEpisodeClick(media, episode.number, supportFragmentManager)
    }
}

@Composable
private fun TvMedia(
    media: Media,
    sourceNames: List<String>,
    sourceIndex: Int,
    sourceIcon: (Int) -> androidx.compose.ui.graphics.ImageBitmap?,
    isDownloads: (Int) -> Boolean,
    onAddExtensions: () -> Unit,
    onSourceSettings: (() -> Unit)?,
    onOpenMedia: (Media) -> Unit,
    episodes: List<Episode>?,
    onPlay: (Episode) -> Unit,
    downloadState: (Episode) -> EpisodeDownload,
    onDownload: (Episode) -> Unit,
    onDownloadMany: (List<String>) -> Unit,
    onCancelDownload: (Episode) -> Unit,
    onDeleteDownload: (Episode) -> Unit,
    onSource: (Int) -> Unit,
    matchedName: String?,
    searchSource: suspend (String) -> List<ShowResponse>,
    onPickMatch: (ShowResponse) -> Unit,
    listVersion: Int,
    onSaveList: (String, Int) -> Unit,
    onRemoveFromList: () -> Unit,
    malSync: TvMalSync?,
    loadMalSyncLanguages: suspend () -> List<ani.dantotsu.connections.malsync.LanguageOption>,
    onMalSyncLanguage: (ani.dantotsu.connections.malsync.LanguageOption) -> Unit,
    favourite: Boolean,
    onFavourite: (() -> Unit)?,
) {
    var editingList by remember { mutableStateOf(false) }
    var pickingSource by remember { mutableStateOf(false) }
    var fixingTitle by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Episode?>(null) }
    var batchDownloading by remember { mutableStateOf(false) }
    var pickingReleaseLanguage by remember { mutableStateOf(false) }
    val sourceName = sourceNames.getOrNull(sourceIndex)
    val watched = media.userProgress ?: 0
    // The next one to watch: the first past the user's progress, else the first there is.
    val next = episodes?.firstOrNull { (it.number.toFloatOrNull() ?: 0f) > watched } ?: episodes?.firstOrNull()
    val playButton = remember { FocusRequester() }
    LaunchedEffect(next != null) { if (next != null) runCatching { playButton.requestFocus() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(media.banner ?: media.cover, Modifier.fillMaxSize())
        // The title and what's under it can't take focus, so focus coming back up into the top section
        // (the buttons beneath them) takes the page all the way to its top: resting the buttons at the
        // usual line left the title above them cut off.
        val pageState = rememberLazyListState()
        val restPx = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }
        val pageSpec = remember(restPx) {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            object : androidx.compose.foundation.gestures.BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    // In the top sections (the title, then the buttons, items 0 and 1): to the very top.
                    // Item offsets count from the content's start, after the top padding; [offset]
                    // from the list's own edge.
                    val shift = -pageState.layoutInfo.viewportStartOffset
                    val within = pageState.layoutInfo.visibleItemsInfo.firstOrNull {
                        it.index <= 1 && offset >= it.offset + shift && offset + size <= it.offset + shift + it.size
                    }
                    if (within == null) return offset - restPx
                    val first = pageState.layoutInfo.visibleItemsInfo.first()
                    // From the first item showing, back past what's scrolled of it — and, if that's
                    // the buttons, past the title above them too (more than enough; the list stops at its top).
                    return -(pageState.firstVisibleItemScrollOffset + if (first.index > 0) containerSize else 0f)
                }
            }
        }
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides pageSpec) {
        LazyColumn(
            state = pageState,
            contentPadding = PaddingValues(start = 48.dp, top = 36.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(Modifier.width(560.dp)) {
                    FittedTitle(media.userPreferredName)
                    Text(
                        metaLine(media),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (media.genres.isNotEmpty()) Text(
                        media.genres.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (malSync?.shown == true) MalSyncReleaseLine(malSync, Modifier.padding(top = 8.dp))
                    Text(
                        plainDescription(media),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            item { Column {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (next != null) Button(
                        onClick = { onPlay(next) },
                        modifier = Modifier.focusRequester(playButton).pointerClick { onPlay(next) },
                    ) {
                        Text(
                            if (watched > 0) stringResource(R.string.tv_continue_episode, next.number)
                            else stringResource(R.string.tv_watch_episode, next.number)
                        )
                    }
                    // Where the title sits on the list, and the way to change it. Read through
                    // listVersion so a save here redraws it.
                    val listLabel = listVersion.let { listStatusLabel(media) } ?: stringResource(R.string.add_list)
                    OutlinedButton(
                        onClick = { editingList = true },
                        modifier = Modifier.pointerClick { editingList = true },
                    ) { Text(listLabel) }
                    if (onFavourite != null) androidx.tv.material3.OutlinedIconButton(
                        onClick = onFavourite,
                        modifier = Modifier.pointerClick(onClick = onFavourite),
                    ) {
                        androidx.tv.material3.Icon(
                            androidx.compose.ui.res.painterResource(
                                if (favourite) R.drawable.ic_round_favorite_24 else R.drawable.ic_round_favorite_border_24
                            ),
                            // Filled while it's a favourite, an outline otherwise, in the button's own colours.
                            contentDescription = stringResource(if (favourite) R.string.tv_favourite_remove else R.string.tv_favourite_add),
                        )
                    }
                    // The release language MALSync follows for this title, and the way to pick another.
                    if (malSync != null) OutlinedButton(
                        onClick = { pickingReleaseLanguage = true },
                        modifier = Modifier.pointerClick { pickingReleaseLanguage = true },
                    ) { MalSyncLanguageLabel(malSync.language) }
                    // The source in use, by its icon and name, and its settings beside it.
                    if (sourceNames.isNotEmpty()) OutlinedButton(
                        onClick = { pickingSource = true },
                        modifier = Modifier.pointerClick { pickingSource = true },
                    ) {
                        SourceIcon(sourceIndex, sourceNames, sourceIcon, isDownloads, Modifier.padding(end = 8.dp).size(20.dp))
                        Text(stringResource(R.string.tv_source, sourceName.orEmpty()))
                    }
                    if (onSourceSettings != null) androidx.tv.material3.OutlinedIconButton(
                        onClick = onSourceSettings,
                        modifier = Modifier.pointerClick(onClick = onSourceSettings),
                    ) {
                        androidx.tv.material3.Icon(
                            androidx.compose.ui.res.painterResource(R.drawable.ic_round_settings_24),
                            contentDescription = stringResource(R.string.tv_source_settings, sourceName.orEmpty()),
                        )
                    }
                    if (sourceNames.isNotEmpty()) OutlinedButton(
                        onClick = { fixingTitle = true },
                        modifier = Modifier.pointerClick { fixingTitle = true },
                    ) {
                        Text(stringResource(R.string.tv_wrong_title))
                    }
                    if (!episodes.isNullOrEmpty() && !isDownloads(sourceIndex)) OutlinedButton(
                        onClick = { batchDownloading = true },
                        modifier = Modifier.pointerClick { batchDownloading = true },
                    ) {
                        androidx.tv.material3.Icon(
                            androidx.compose.ui.res.painterResource(R.drawable.ic_download_24),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp).size(20.dp),
                        )
                        Text(stringResource(R.string.tv_download_episodes))
                    }
                }
                if (matchedName != null && sourceName != null) Text(
                    stringResource(R.string.tv_matched_as, sourceName, matchedName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } }
            item {
                when {
                    sourceNames.isEmpty() -> Status(stringResource(R.string.tv_no_extensions))
                    episodes == null -> Status(stringResource(R.string.tv_finding_episodes, sourceName.orEmpty()))
                    episodes.isEmpty() -> Status(stringResource(R.string.tv_no_episodes, sourceName.orEmpty()))
                    else -> Column {
                        Row(Modifier.padding(bottom = 12.dp, end = 48.dp), verticalAlignment = Alignment.Bottom) {
                            Text(stringResource(R.string.tv_episodes), style = MaterialTheme.typography.titleLarge)
                            // Holding OK is how a remote gets at the rest, and nothing on screen says so otherwise.
                            if (!isDownloads(sourceIndex)) Text(
                                stringResource(R.string.tv_episode_hold_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, bottom = 3.dp),
                            )
                        }
                        val rowState = rememberLazyListState()
                        // Opens at the next episode rather than at episode 1 of a long series.
                        LaunchedEffect(episodes.size) {
                            val at = episodes.indexOf(next).coerceAtLeast(0)
                            if (at > 0) rowState.scrollToItem(at)
                        }
                        // A little inset, so the first card's focus outline isn't cut by the row's edge.
                        FocusRestingAt(8.dp) { LazyRow(
                            state = rowState,
                            contentPadding = PaddingValues(start = 8.dp, end = 48.dp, top = 12.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            items(episodes, key = { it.number }) { episode ->
                                EpisodeCard(
                                    episode = episode,
                                    fallbackImage = media.banner ?: media.cover,
                                    watched = (episode.number.toFloatOrNull() ?: Float.MAX_VALUE) <= watched,
                                    download = downloadState(episode),
                                    onClick = { onPlay(episode) },
                                    // From the downloads themselves there's nothing to fetch, only to play.
                                    onLongClick = if (isDownloads(sourceIndex)) null else ({ actionsFor = episode }),
                                )
                            }
                        } }
                    }
                }
            }
            tvMediaInfo(media, onOpenMedia)
        }
        }
    }

    if (editingList) TvListEditor(
        media = media,
        onSave = { status, progress -> editingList = false; onSaveList(status, progress) },
        onRemove = { editingList = false; onRemoveFromList() },
        onDismiss = { editingList = false },
    )

    if (fixingTitle) WrongTitleDialog(
        initialQuery = media.mainName(),
        sourceName = sourceName.orEmpty(),
        search = searchSource,
        onPick = { fixingTitle = false; onPickMatch(it) },
        onDismiss = { fixingTitle = false },
    )

    actionsFor?.let { episode ->
        EpisodeActions(
            episode = episode.number,
            state = downloadState(episode),
            onPlay = { actionsFor = null; onPlay(episode) },
            onDownload = { actionsFor = null; onDownload(episode) },
            onCancel = { actionsFor = null; onCancelDownload(episode) },
            onDelete = { actionsFor = null; onDeleteDownload(episode) },
            onDismiss = { actionsFor = null },
        )
    }

    if (pickingReleaseLanguage && malSync != null) MalSyncLanguageDialog(
        current = malSync.language.id,
        load = loadMalSyncLanguages,
        onPick = { pickingReleaseLanguage = false; onMalSyncLanguage(it) },
        onDismiss = { pickingReleaseLanguage = false },
    )

    if (batchDownloading && episodes != null) BatchDownloadDialog(
        episodes = episodes.map { it.number },
        watched = watched,
        // Already there or on its way: nothing to start again.
        skip = { number -> episodes.firstOrNull { it.number == number }?.let { downloadState(it) != EpisodeDownload.None } == true },
        onDownload = { batchDownloading = false; onDownloadMany(it) },
        onDismiss = { batchDownloading = false },
    )

    if (pickingSource) SourcePicker(
        names = sourceNames,
        icon = sourceIcon,
        isDownloads = isDownloads,
        onAddExtensions = { pickingSource = false; onAddExtensions() },
        selected = sourceIndex,
        onPick = { pickingSource = false; onSource(it) },
        onDismiss = { pickingSource = false },
    )
}

@Composable
private fun Status(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * An episode: its thumbnail (or the series' banner), number and title; dimmed once watched, marked
 * once downloaded or while downloading. Held, its actions ([onLongClick]).
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun EpisodeCard(
    episode: Episode,
    fallbackImage: String?,
    watched: Boolean,
    download: EpisodeDownload,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Column(Modifier.width(200.dp)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).pointerClick(onLongClick = onLongClick, onClick = onClick),
            shape = CardDefaults.shape(CARD_SHAPE),
            scale = CardDefaults.scale(focusedScale = FOCUSED_SCALE),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(FOCUS_OUTLINE, MaterialTheme.colorScheme.primary), shape = CARD_SHAPE),
            ),
        ) {
            GlideImage(
                model = episode.thumb?.url ?: fallbackImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (watched) 0.45f else 1f),
            )
            Box(Modifier.fillMaxSize()) { EpisodeDownloadMarks(download) }
        }
        Text(
            stringResource(R.string.tv_episode_n, episode.number),
            style = MaterialTheme.typography.titleSmall,
            color = if (watched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp),
        )
        // A title that only repeats the number ("Episode 3") says nothing the line above doesn't.
        episode.title?.takeIf { MediaNameAdapter.removeEpisodeNumberCompletely(it).isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The installed sources, one focusable row each, the current one first in focus. */
@Composable
private fun SourcePicker(
    names: List<String>,
    icon: (Int) -> androidx.compose.ui.graphics.ImageBitmap?,
    isDownloads: (Int) -> Boolean,
    onAddExtensions: () -> Unit,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(420.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.tv_pick_source),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                LazyColumn(Modifier.height(360.dp)) {
                    itemsIndexed(names) { index, name ->
                        ListItem(
                            selected = index == selected,
                            onClick = { onPick(index) },
                            headlineContent = { Text(name) },
                            leadingContent = { SourceIcon(index, names, icon, isDownloads, Modifier.size(28.dp)) },
                            modifier = (if (index == selected) Modifier.focusRequester(current) else Modifier)
                                .pointerClick { onPick(index) },
                        )
                    }
                    item {
                        ListItem(
                            selected = false,
                            onClick = onAddExtensions,
                            headlineContent = { Text(stringResource(R.string.tv_add_extensions)) },
                            leadingContent = {
                                androidx.tv.material3.Icon(
                                    androidx.compose.ui.res.painterResource(R.drawable.ic_round_add_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                )
                            },
                            modifier = Modifier.pointerClick(onClick = onAddExtensions),
                        )
                    }
                }
            }
        }
    }
    // A frame on: the dialog's window isn't there to take focus at first.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { current.requestFocus() }
    }
}

/** A source's icon: its extension's, or the download icon for the downloaded episodes. */
@Composable
private fun SourceIcon(
    index: Int,
    names: List<String>,
    icon: (Int) -> androidx.compose.ui.graphics.ImageBitmap?,
    isDownloads: (Int) -> Boolean,
    modifier: Modifier,
) {
    val bitmap = remember(index, names) { icon(index) }
    val downloads = remember(index, names) { isDownloads(index) }
    when {
        bitmap != null -> androidx.compose.foundation.Image(bitmap, contentDescription = null, modifier = modifier)
        downloads -> androidx.tv.material3.Icon(
            androidx.compose.ui.res.painterResource(R.drawable.ic_download_24),
            contentDescription = null,
            modifier = modifier,
        )
    }
}
