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
import ani.dantotsu.connections.mangaupdates.AniListQuickSearchDialogFragment
import ani.dantotsu.connections.simkl.SimklApi
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivitySimklMediaBinding
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.openOrCopyAnilistLink
import ani.dantotsu.others.ImageViewDialog
import ani.dantotsu.px
import ani.dantotsu.settings.bindQuickSettings
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Standalone Simkl anime screen — the Simkl search-result destination, mirroring
 * [MangaBakaMediaActivity]. Hands the loaded model to the shared [SimklMediaRenderer].
 */
class SimklMediaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SIMKL_ID = "simkl_media_id"
        const val EXTRA_OPEN_EPISODES = "simkl_open_episodes"
    }

    private lateinit var binding: ActivitySimklMediaBinding
    private lateinit var episodes: TrackerEpisodesController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivitySimklMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.simklMediaBottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            height += navBarHeight
        }
        binding.simklMediaBottomBar.setPadding(0, 0, 0, navBarHeight)
        binding.simklMediaPages.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }
        binding.simklMediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight + 16f.px
        }
        binding.quickSettings.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight + 16f.px
        }
        binding.quickSettings.bindQuickSettings(this)
        binding.simklMediaClose.setOnClickListener { finish() }

        episodes = TrackerEpisodesController(
            activity = this,
            bottomBar = binding.simklMediaBottomBar,
            infoScroll = binding.simklMediaInfoScroll,
            episodesScroll = binding.simklMediaEpisodesScroll,
            episodesContent = binding.simklMediaEpisodesContent,
            episodesEmpty = binding.simklEpisodesEmpty,
            chipScroll = binding.simklEpisodesChipScroll,
            chipGroup = binding.simklEpisodesChipGroup,
            scrollTopButton = binding.simklEpisodesScrollTop,
            pagesWidthSource = binding.simklMediaPages,
            startOnEpisodes = intent.getBooleanExtra(EXTRA_OPEN_EPISODES, false),
        ).also { it.setup() }

        val simklId = intent.getLongExtra(EXTRA_SIMKL_ID, -1L).takeIf { it > 0 }
            ?: intent.data?.pathSegments?.getOrNull(1)?.toLongOrNull()
            ?: run { finish(); return }

        lifecycleScope.launch {
            val fullDeferred = async(Dispatchers.IO) { SimklApi.getAnime(simklId) }
            val episodesDeferred = async(Dispatchers.IO) { SimklApi.getEpisodes(simklId) }
            val full = fullDeferred.await()
            if (full == null) {
                episodesDeferred.cancel()
                binding.simklMediaProgress.visibility = View.GONE
                Toast.makeText(this@SimklMediaActivity, getString(R.string.simkl_no_data_title), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            // Simkl only carries a short genre list; pull the fuller tag list from AniList when the
            // record maps to one (same list the Simkl website shows).
            val anilistId = full.ids?.anilist?.toIntOrNull()
            val tagsDeferred = async(Dispatchers.IO) {
                anilistId?.let { runCatching { Anilist.query.getMediaTags(it) }.getOrNull() }
            }
            val episodeList = episodesDeferred.await()
            val anilistTags = tagsDeferred.await()
            setupHeader(full)
            setupSourceButtons(full)
            binding.simklMediaProgress.visibility = View.GONE

            val info = FragmentMediaInfoBinding.inflate(layoutInflater)
            SimklMediaRenderer.render(
                activity = this@SimklMediaActivity,
                info = info,
                contentHost = binding.simklMediaContent,
                full = full,
                anilistTags = anilistTags,
                onGenreClick = { genre -> startSimklSearch(genre) },
                onSimklMediaClick = { id ->
                    startActivity(
                        Intent(this@SimklMediaActivity, SimklMediaActivity::class.java)
                            .putExtra(EXTRA_SIMKL_ID, id)
                    )
                },
            )

            episodes.coverUrl = SimklApi.posterUrl(full.poster, "_ca")
            episodes.setEpisodes(SimklMediaRenderer.toEpisodeRows(episodeList))
            episodes.revealCurrentTab()
        }
    }

    private fun setupHeader(full: SimklApi.SimklAnimeFull) {
        val posterUrl = SimklApi.posterUrl(full.poster, "_ca")
        val bannerUrl = SimklApi.posterUrl(full.fanart, "_w") ?: posterUrl
        if (posterUrl != null) binding.simklMediaCover.loadImage(posterUrl)
        if (bannerUrl != null) blurImage(binding.simklMediaBanner, bannerUrl)

        val title = full.title ?: full.enTitle ?: getString(R.string.unknown)
        binding.simklMediaTitle.text = title
        binding.simklMediaTitle.setOnLongClickListener { copyToClipboard(title); true }
        binding.simklMediaCover.setOnLongClickListener {
            ImageViewDialog.newInstance(this, getString(R.string.cover, title), posterUrl)
        }
        val score = full.ratings?.simkl?.rating?.takeIf { it > 0 }
        binding.simklMediaScore.text = score?.let { "★ " + String.format(Locale.US, "%.1f", it) } ?: ""
    }

    private fun setupSourceButtons(full: SimklApi.SimklAnimeFull) {
        // Simkl / MAL are reachable by long-pressing the search result. The button either views the
        // linked AniList entry or — when Simkl has no anilist id — opens a quick-search against it.
        val titles = (listOfNotNull(full.title, full.enTitle) + full.altTitles.orEmpty().mapNotNull { it.name })
            .filter { it.isNotBlank() }.distinct()
        binding.simklMediaSourceButtons.visibility = View.VISIBLE
        binding.simklMediaAnilistBtn.visibility = View.VISIBLE
        val anilistId = full.ids?.anilist?.toIntOrNull()
        if (anilistId != null) {
            binding.simklMediaAnilistBtn.setText(R.string.comick_open_anilist)
            binding.simklMediaAnilistBtn.setOnClickListener {
                openOrCopyAnilistLink("https://anilist.co/anime/$anilistId")
            }
        } else {
            binding.simklMediaAnilistBtn.setText(R.string.comick_search_anilist)
            binding.simklMediaAnilistBtn.setOnClickListener {
                AniListQuickSearchDialogFragment.newInstance(
                    titles = ArrayList(titles),
                    type = AniListQuickSearchDialogFragment.TYPE_ANIME,
                ).show(supportFragmentManager, "simkl_anilist_quick_search")
            }
        }
    }

    private fun startSimklSearch(genre: String) {
        startActivity(
            Intent(this, SearchActivity::class.java)
                .putExtra("type", SearchType.SIMKL.toAnilistString())
                .putExtra("query", genre)
                .putExtra("search", true)
        )
    }
}
