package ani.dantotsu.media

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.bindScrollToTop
import ani.dantotsu.blurImage
import ani.dantotsu.databinding.ItemChipBinding
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
import nl.joery.animatedbottombar.AnimatedBottomBar
import java.util.Locale

/**
 * Standalone Simkl anime screen — the Simkl search-result destination, mirroring
 * [MangaBakaMediaActivity]. Hands the loaded model to the shared [SimklMediaRenderer].
 */
class SimklMediaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SIMKL_ID = "simkl_media_id"
    }

    private lateinit var binding: ActivitySimklMediaBinding
    private var currentTabIndex = 0
    private var allEpisodes: List<SimklApi.SimklEpisode> = emptyList()

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

        setupBottomBar()

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
            val episodes = episodesDeferred.await()
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

            allEpisodes = episodes
            populateEpisodes()

            (if (currentTabIndex == 0) binding.simklMediaInfoScroll else binding.simklMediaEpisodesScroll)
                .visibility = View.VISIBLE
        }
    }

    private fun episodePageSize(total: Int): Int {
        val d = total / 10.0
        return when { d < 25 -> 25; d < 50 -> 50; else -> 100 }
    }

    private fun populateEpisodes() {
        binding.simklMediaEpisodesContent.removeAllViews()
        binding.simklEpisodesChipGroup.removeAllViews()

        if (allEpisodes.isEmpty()) {
            binding.simklEpisodesChipScroll.visibility = View.GONE
            binding.simklEpisodesEmpty.visibility = View.VISIBLE
            return
        }
        binding.simklEpisodesEmpty.visibility = View.GONE

        val total = allEpisodes.size
        val limit = episodePageSize(total)
        if (total <= limit) {
            binding.simklEpisodesChipScroll.visibility = View.GONE
            showEpisodeRange(0, total - 1)
            return
        }

        binding.simklEpisodesChipScroll.visibility = View.VISIBLE
        val groupCount = (total + limit - 1) / limit
        for (groupIdx in 0 until groupCount) {
            val startIdx = groupIdx * limit
            val endIdx = minOf(startIdx + limit - 1, total - 1)
            val startNum = allEpisodes[startIdx].episode ?: (startIdx + 1)
            val endNum = allEpisodes[endIdx].episode ?: (endIdx + 1)

            val chip = ItemChipBinding.inflate(layoutInflater, binding.simklEpisodesChipGroup, false).root
            chip.isCheckable = true
            chip.text = getString(R.string.episode_range_format, startNum, endNum)
            chip.setTextColor(ContextCompat.getColorStateList(this, R.color.chip_text_color))
            chip.setOnClickListener {
                chip.isChecked = true
                showEpisodeRange(startIdx, endIdx)
            }
            binding.simklEpisodesChipGroup.addView(chip)
            if (groupIdx == 0) chip.isChecked = true
        }
        showEpisodeRange(0, minOf(limit - 1, total - 1))
    }

    private fun showEpisodeRange(startIdx: Int, endIdx: Int) {
        binding.simklMediaEpisodesContent.removeAllViews()
        val slice = allEpisodes.subList(startIdx, endIdx + 1)
        SimklMediaRenderer.renderEpisodes(
            this, binding.simklMediaEpisodesContent, slice, firstNumber = startIdx + 1,
        )
        binding.simklMediaEpisodesScroll.smoothScrollTo(0, 0)
    }

    private fun setupBottomBar() {
        val navBar = binding.simklMediaBottomBar
        navBar.addTab(navBar.createTab(R.drawable.ic_round_info_24, R.string.info, R.id.info))
        navBar.addTab(navBar.createTab(R.drawable.ic_round_playlist_play_24, R.string.eps, R.id.watch))
        navBar.selectTabAt(0)
        binding.simklMediaInfoScroll.visibility = View.GONE
        binding.simklMediaEpisodesScroll.visibility = View.GONE
        binding.simklEpisodesScrollTop.bindScrollToTop(binding.simklMediaEpisodesScroll)
        navBar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(
                lastIndex: Int, lastTab: AnimatedBottomBar.Tab?, newIndex: Int, newTab: AnimatedBottomBar.Tab,
            ) {
                if (newIndex == currentTabIndex) return
                slideToTab(currentTabIndex, newIndex)
                currentTabIndex = newIndex
            }
        })
    }

    private fun slideToTab(from: Int, to: Int) {
        val outView = if (from == 0) binding.simklMediaInfoScroll else binding.simklMediaEpisodesScroll
        val inView = if (to == 0) binding.simklMediaInfoScroll else binding.simklMediaEpisodesScroll
        if (!outView.isVisible) return
        val width = binding.simklMediaPages.width.toFloat().takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.toFloat()
        val goRight = to > from
        inView.translationX = if (goRight) width else -width
        inView.visibility = View.VISIBLE
        outView.animate().translationX(if (goRight) -width else width).setDuration(280)
            .withEndAction { outView.visibility = View.GONE; outView.translationX = 0f }.start()
        inView.animate().translationX(0f).setDuration(280).start()
        if (to != 1) binding.simklEpisodesScrollTop.visibility = View.GONE
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
