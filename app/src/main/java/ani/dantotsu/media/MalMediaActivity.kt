package ani.dantotsu.media

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mal.MALAlternativeTitles
import ani.dantotsu.connections.mal.MALAnimeResponse
import ani.dantotsu.connections.mal.MALMangaResponse
import ani.dantotsu.connections.mal.MALPicture
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.connections.mangaupdates.AniListQuickSearchDialogFragment
import ani.dantotsu.connections.mangaupdates.MangaUpdatesQuickSearchDialogFragment
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityMalMediaBinding
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.openMangaUpdatesSeriesInApp
import ani.dantotsu.openOrCopyAnilistLink
import ani.dantotsu.others.ImageViewDialog
import ani.dantotsu.px
import ani.dantotsu.settings.bindQuickSettings
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Standalone MAL media screen — the MAL search-result destination, mirroring [KitsuMediaActivity]
 * but without an Episodes tab (the API's episode data has no synopsis/thumbnail per episode, too
 * sparse to be worth it). Fetches by id from the official v2 API (public — a client-id header
 * covers anonymous access, see [MAL.query]) and hands the loaded model to [MalMediaRenderer].
 */
class MalMediaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_ID = "mal_media_id"
        const val EXTRA_IS_ANIME = "mal_is_anime"
    }

    private lateinit var binding: ActivityMalMediaBinding
    private var isAnime = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityMalMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.malMediaPages.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.malMediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight + 16f.px
        }
        binding.quickSettings.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight + 16f.px
        }
        binding.quickSettings.bindQuickSettings(this)
        binding.malMediaClose.setOnClickListener { finish() }

        isAnime = intent.getBooleanExtra(EXTRA_IS_ANIME, false)
        val malId = intent.getIntExtra(EXTRA_MEDIA_ID, -1).takeIf { it > 0 }
            ?: intent.data?.pathSegments?.getOrNull(1)?.toIntOrNull()
            ?: run { finish(); return }

        lifecycleScope.launch {
            // No dependency between these two — resolve the AniList id in parallel with the MAL
            // fetch instead of only after it lands, unlike Simkl's tags-after-full chain.
            val animeDeferred = if (isAnime) async(Dispatchers.IO) { MAL.query.getAnimeDetails(malId) } else null
            val mangaDeferred = if (!isAnime) async(Dispatchers.IO) { MAL.query.getMangaDetails(malId) } else null
            val anilistDeferred = async(Dispatchers.IO) {
                runCatching { Anilist.query.getMedia(malId, mal = true, type = if (isAnime) "ANIME" else "MANGA") }.getOrNull()
            }
            // MangaUpdates only indexes manga, and the official API carries no MU id of its own —
            // resolve through MangaBaka's cross-source mapping, same as Kitsu's manga button does
            // through its own /mappings (a different source, since MAL's API has no such field).
            val muDeferred = if (!isAnime) async(Dispatchers.IO) {
                runCatching { MangaBakaApi.getMangaUpdatesIdFromAnilist(anilistId = null, malId = malId) }.getOrNull()
            } else null

            val anime = animeDeferred?.await()
            val manga = mangaDeferred?.await()
            if (anime == null && manga == null) {
                binding.malMediaProgress.visibility = View.GONE
                Toast.makeText(this@MalMediaActivity, getString(R.string.mal_no_data_title), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            setupHeader(anime, manga)
            setupSourceButtons(anime, manga, anilistDeferred.await()?.id, muDeferred?.await())
            binding.malMediaProgress.visibility = View.GONE

            // Turn MAL's recommendation list into in-app AniList media, same as the Simkl page —
            // batch-resolve by MAL id, dropping any that don't map to AniList.
            val recMalIds = (anime?.recommendations ?: manga?.recommendations).orEmpty()
                .map { it.node.id }
            val anilistRecs = if (recMalIds.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
                runCatching {
                    Anilist.query.getMediaBatch(recMalIds, mal = true, mediaType = if (isAnime) "ANIME" else "MANGA")
                }.getOrDefault(emptyList())
            }

            val info = FragmentMediaInfoBinding.inflate(layoutInflater)
            if (anime != null) {
                MalMediaRenderer.renderAnime(
                    activity = this@MalMediaActivity,
                    info = info,
                    contentHost = binding.malMediaContent,
                    full = anime,
                    onGenreClick = { name -> startMalSearch(name) },
                    onRelationClick = { relMalId, relIsAnime -> openMedia(relMalId, relIsAnime) },
                    anilistRecs = anilistRecs,
                )
            } else if (manga != null) {
                MalMediaRenderer.renderManga(
                    activity = this@MalMediaActivity,
                    info = info,
                    contentHost = binding.malMediaContent,
                    full = manga,
                    onGenreClick = { name -> startMalSearch(name) },
                    onRelationClick = { relMalId, relIsAnime -> openMedia(relMalId, relIsAnime) },
                    anilistRecs = anilistRecs,
                )
            }

            binding.malMediaInfoScroll.visibility = View.VISIBLE
        }
    }

    private fun openMedia(malId: Int, relIsAnime: Boolean) {
        startActivity(
            Intent(this, MalMediaActivity::class.java)
                .putExtra(EXTRA_MEDIA_ID, malId)
                .putExtra(EXTRA_IS_ANIME, relIsAnime)
        )
    }

    private fun setupHeader(anime: MALAnimeResponse?, manga: MALMangaResponse?) {
        val mainPicture: MALPicture? = anime?.mainPicture ?: manga?.mainPicture
        val posterUrl = mainPicture?.large ?: mainPicture?.medium
        if (posterUrl != null) binding.malMediaCover.loadImage(posterUrl)
        if (posterUrl != null) blurImage(binding.malMediaBanner, posterUrl)

        val altTitles: MALAlternativeTitles? = anime?.alternativeTitles ?: manga?.alternativeTitles
        val rawTitle = anime?.title ?: manga?.title
        val title = altTitles?.en?.takeIf { it.isNotBlank() } ?: altTitles?.ja?.takeIf { it.isNotBlank() }
            ?: rawTitle ?: getString(R.string.unknown)
        binding.malMediaTitle.text = title
        binding.malMediaTitle.setOnLongClickListener { copyToClipboard(title); true }
        binding.malMediaCover.setOnLongClickListener {
            ImageViewDialog.newInstance(this, getString(R.string.cover, title), posterUrl)
        }
        val score = anime?.mean ?: manga?.mean
        binding.malMediaScore.text = score?.let { "★ " + String.format(Locale.US, "%.1f", it) } ?: ""
    }

    private fun setupSourceButtons(anime: MALAnimeResponse?, manga: MALMangaResponse?, anilistId: Int?, muId: Long?) {
        val kind = if (isAnime) "anime" else "manga"
        val titles = titleList(anime, manga)

        binding.malMediaSourceButtons.visibility = View.VISIBLE
        binding.malMediaAnilistBtn.visibility = View.VISIBLE
        if (anilistId != null) {
            binding.malMediaAnilistBtn.setText(R.string.comick_open_anilist)
            binding.malMediaAnilistBtn.setOnClickListener {
                openOrCopyAnilistLink("https://anilist.co/$kind/$anilistId")
            }
        } else {
            binding.malMediaAnilistBtn.setText(R.string.comick_search_anilist)
            binding.malMediaAnilistBtn.setOnClickListener {
                AniListQuickSearchDialogFragment.newInstance(
                    titles = ArrayList(titles),
                    type = if (isAnime) AniListQuickSearchDialogFragment.TYPE_ANIME
                    else AniListQuickSearchDialogFragment.TYPE_MANGA,
                ).show(supportFragmentManager, "mal_anilist_quick_search")
            }
        }

        // MangaUpdates only indexes manga.
        if (isAnime) {
            binding.malMediaMuBtn.visibility = View.GONE
            return
        }
        binding.malMediaMuBtn.visibility = View.VISIBLE
        if (muId != null) {
            binding.malMediaMuBtn.setText(R.string.comick_open_mangaupdates)
            binding.malMediaMuBtn.setOnClickListener {
                val url = "https://www.mangaupdates.com/series.html?id=$muId"
                if (!openMangaUpdatesSeriesInApp(url)) openLinkInBrowser(url)
            }
        } else {
            binding.malMediaMuBtn.setText(R.string.mu_search_title)
            binding.malMediaMuBtn.setOnClickListener {
                MangaUpdatesQuickSearchDialogFragment.newInstance(titles = ArrayList(titles))
                    .show(supportFragmentManager, "mal_mu_quick_search")
            }
        }
    }

    private fun titleList(anime: MALAnimeResponse?, manga: MALMangaResponse?): List<String> {
        val out = LinkedHashSet<String>()
        val altTitles = anime?.alternativeTitles ?: manga?.alternativeTitles
        val rawTitle = anime?.title ?: manga?.title
        altTitles?.en?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        rawTitle?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        altTitles?.ja?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        altTitles?.synonyms?.forEach { t -> t.takeIf { it.isNotBlank() }?.let { out.add(it) } }
        return out.toList()
    }

    /**
     * Genre chips have no id-based filter to seed anymore (the official search API takes only
     * free text) — search by the genre's own name instead.
     */
    private fun startMalSearch(genreName: String) {
        startActivity(
            Intent(this, SearchActivity::class.java)
                .putExtra("type", (if (isAnime) SearchType.MAL_ANIME else SearchType.MAL).toAnilistString())
                .putExtra("query", genreName)
                .putExtra("search", true)
        )
    }
}
