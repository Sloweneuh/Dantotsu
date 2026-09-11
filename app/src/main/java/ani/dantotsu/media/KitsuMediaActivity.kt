package ani.dantotsu.media

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.kitsu.KitsuApi
import ani.dantotsu.connections.mangaupdates.AniListQuickSearchDialogFragment
import ani.dantotsu.connections.mangaupdates.MangaUpdatesQuickSearchDialogFragment
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityKitsuMediaBinding
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadCoverImage
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Standalone Kitsu media screen — the Kitsu search-result destination, mirroring
 * [MangaBakaMediaActivity]. Fetches a media by id and hands the loaded model to
 * [KitsuMediaRenderer], which is shared with the future Kitsu info tab.
 */
class KitsuMediaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_ID = "kitsu_media_id"
        const val EXTRA_IS_ANIME = "kitsu_is_anime"
        const val EXTRA_OPEN_EPISODES = "kitsu_open_episodes"
        // Lets the shared-element transition show the real cover immediately instead of an
        // empty view while the full series details are still being fetched over the network.
        const val EXTRA_COVER_URL = "kitsu_cover_url"
    }

    private lateinit var binding: ActivityKitsuMediaBinding
    private var isAnime = false
    private var episodes: TrackerEpisodesController? = null

    private var enterTransitionStarted = false

    // Releasing on doOnPreDraw alone can fire before Glide has actually put pixels into the
    // cover ImageView (even a passed-in cover URL resolves on a later frame, never
    // synchronously), animating an empty view. Gate on whichever finishes last.
    private fun maybeStartEnterTransition() {
        if (enterTransitionStarted) return
        enterTransitionStarted = true
        startPostponedEnterTransition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must run before postponeEnterTransition(): applyTheme() calls setTheme(), and until
        // that lands, Window.FEATURE_ACTIVITY_TRANSITIONS reads false, which breaks the shared
        // element round-trip (notably the return-to-list transition on back navigation).
        ThemeManager(this).applyTheme()
        postponeEnterTransition()
        // Otherwise the rest of the content has no enter transition of its own and appears fully
        // opaque immediately, on top of the still-animating shared element cover.
        window.allowEnterTransitionOverlap = false
        binding = ActivityKitsuMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Match the transition name to the exact search result that was tapped. Every row
        // shares the same static XML transitionName, so without this, the return trip's
        // name-based lookup in the calling window can land on any other view still carrying it.
        intent.getStringExtra("transitionName")?.let {
            ViewCompat.setTransitionName(binding.kitsuMediaCover, it)
        }
        val passedCoverUrl = intent.getStringExtra(EXTRA_COVER_URL)
        if (passedCoverUrl != null) {
            binding.kitsuMediaCover.loadCoverImage(passedCoverUrl) { maybeStartEnterTransition() }
        } else {
            binding.root.doOnPreDraw { maybeStartEnterTransition() }
        }
        // Safety net: never hang the shared-element transition forever.
        binding.root.postDelayed({ maybeStartEnterTransition() }, 300)
        initActivity(this)

        binding.kitsuMediaBottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            height += navBarHeight
        }
        binding.kitsuMediaBottomBar.setPadding(0, 0, 0, navBarHeight)
        binding.kitsuMediaPages.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }
        binding.kitsuMediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight + 16f.px
        }
        binding.quickSettings.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight + 16f.px
        }
        binding.quickSettings.bindQuickSettings(this)
        // Plain finish() skips the reverse shared-element transition — the default
        // Activity.onBackPressed() (which this dispatches to) calls finishAfterTransition()
        // instead, which is what actually plays the cover flying back to the list.
        binding.kitsuMediaClose.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        isAnime = intent.getBooleanExtra(EXTRA_IS_ANIME, false)
        val mediaId = intent.getStringExtra(EXTRA_MEDIA_ID)
            ?: intent.data?.pathSegments?.getOrNull(1)
            ?: run { finish(); return }

        // Episodes tab only makes sense for anime — Kitsu manga has no episode list.
        if (isAnime) {
            episodes = TrackerEpisodesController(
                activity = this,
                bottomBar = binding.kitsuMediaBottomBar,
                infoScroll = binding.kitsuMediaInfoScroll,
                episodesScroll = binding.kitsuMediaEpisodesScroll,
                episodesContent = binding.kitsuMediaEpisodesContent,
                episodesEmpty = binding.kitsuEpisodesEmpty,
                chipScroll = binding.kitsuEpisodesChipScroll,
                chipGroup = binding.kitsuEpisodesChipGroup,
                scrollTopButton = binding.kitsuEpisodesScrollTop,
                pagesWidthSource = binding.kitsuMediaPages,
                startOnEpisodes = intent.getBooleanExtra(EXTRA_OPEN_EPISODES, false),
            ).also { it.setup() }
        } else {
            binding.kitsuMediaBottomBar.visibility = View.GONE
            binding.kitsuMediaPages.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = navBarHeight }
        }

        lifecycleScope.launch {
            val full = withContext(Dispatchers.IO) { KitsuApi.getMediaFull(isAnime, mediaId) }
            if (full == null) {
                binding.kitsuMediaProgress.visibility = View.GONE
                Toast.makeText(this@KitsuMediaActivity, getString(R.string.kitsu_no_data_title), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val episodeList = if (isAnime) withContext(Dispatchers.IO) { KitsuApi.getEpisodes(mediaId) } else emptyList()
            setupHeader(full)
            setupSourceButtons(full)
            binding.kitsuMediaProgress.visibility = View.GONE

            val info = FragmentMediaInfoBinding.inflate(layoutInflater)
            KitsuMediaRenderer.render(
                activity = this@KitsuMediaActivity,
                info = info,
                contentHost = binding.kitsuMediaContent,
                full = full,
                isAnime = isAnime,
                onCategoryClick = { slug, name -> startKitsuSearch(slug, name) },
                onRelationClick = { rel ->
                    startActivity(
                        Intent(this@KitsuMediaActivity, KitsuMediaActivity::class.java)
                            .putExtra(EXTRA_MEDIA_ID, rel.id)
                            .putExtra(EXTRA_IS_ANIME, rel.isAnime)
                    )
                },
            )

            val ctrl = episodes
            if (ctrl != null) {
                val media = full.media
                ctrl.coverUrl = media.posterImage?.original ?: media.posterImage?.medium ?: media.posterImage?.small
                ctrl.setEpisodes(KitsuMediaRenderer.toEpisodeRows(episodeList))
                ctrl.revealCurrentTab()
            } else {
                binding.kitsuMediaInfoScroll.visibility = View.VISIBLE
            }
        }
    }

    private fun setupHeader(full: KitsuApi.KitsuMediaFull) {
        val media = full.media
        val posterUrl = media.posterImage?.original ?: media.posterImage?.medium ?: media.posterImage?.small
        val bannerUrl = media.coverImage?.original ?: media.coverImage?.medium ?: posterUrl
        if (posterUrl != null) binding.kitsuMediaCover.loadImage(posterUrl)
        if (bannerUrl != null) blurImage(binding.kitsuMediaBanner, bannerUrl)

        val title = media.canonicalTitle
            ?: media.titles?.values?.firstOrNull { !it.isNullOrBlank() }
            ?: getString(R.string.unknown)
        binding.kitsuMediaTitle.text = title
        binding.kitsuMediaTitle.setOnLongClickListener { copyToClipboard(title); true }
        binding.kitsuMediaCover.setOnLongClickListener {
            ImageViewDialog.newInstance(this, getString(R.string.cover, title), posterUrl)
        }
        val score = media.averageRating?.toDoubleOrNull()?.let { it / 10.0 }
        binding.kitsuMediaScore.text = score?.let { "★ " + String.format(Locale.US, "%.1f", it) } ?: ""
    }

    private fun setupSourceButtons(full: KitsuApi.KitsuMediaFull) {
        // Kitsu itself / MAL are reachable by long-pressing the search result, so the buttons here
        // mirror Comick/MangaBaka: view the linked AniList / MangaUpdates entry, or — when Kitsu
        // has no such mapping — a quick-search sheet against it.
        val kind = if (isAnime) "anime" else "manga"
        val titles = titleList(full.media)

        binding.kitsuMediaSourceButtons.visibility = View.VISIBLE
        binding.kitsuMediaAnilistBtn.visibility = View.VISIBLE
        val anilistId = full.anilistId
        if (anilistId != null) {
            binding.kitsuMediaAnilistBtn.setText(R.string.comick_open_anilist)
            binding.kitsuMediaAnilistBtn.setOnClickListener {
                openOrCopyAnilistLink("https://anilist.co/$kind/$anilistId")
            }
        } else {
            binding.kitsuMediaAnilistBtn.setText(R.string.comick_search_anilist)
            binding.kitsuMediaAnilistBtn.setOnClickListener {
                AniListQuickSearchDialogFragment.newInstance(
                    titles = ArrayList(titles),
                    type = if (isAnime) AniListQuickSearchDialogFragment.TYPE_ANIME
                    else AniListQuickSearchDialogFragment.TYPE_MANGA,
                ).show(supportFragmentManager, "kitsu_anilist_quick_search")
            }
        }

        // MangaUpdates only indexes manga.
        if (isAnime) {
            binding.kitsuMediaMuBtn.visibility = View.GONE
            return
        }
        binding.kitsuMediaMuBtn.visibility = View.VISIBLE
        val muId = full.muId?.trim()
        if (!muId.isNullOrBlank()) {
            binding.kitsuMediaMuBtn.setText(R.string.comick_open_mangaupdates)
            binding.kitsuMediaMuBtn.setOnClickListener {
                val url = if (muId.all { it.isDigit() }) {
                    "https://www.mangaupdates.com/series.html?id=$muId"
                } else {
                    "https://www.mangaupdates.com/series/$muId"
                }
                if (!openMangaUpdatesSeriesInApp(url)) openLinkInBrowser(url)
            }
        } else {
            binding.kitsuMediaMuBtn.setText(R.string.mu_search_title)
            binding.kitsuMediaMuBtn.setOnClickListener {
                MangaUpdatesQuickSearchDialogFragment.newInstance(titles = ArrayList(titles))
                    .show(supportFragmentManager, "kitsu_mu_quick_search")
            }
        }
    }

    private fun titleList(media: KitsuApi.KitsuMedia): List<String> {
        val out = LinkedHashSet<String>()
        media.canonicalTitle?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        media.titles?.values?.forEach { t -> t?.takeIf { it.isNotBlank() }?.let { out.add(it) } }
        media.abbreviatedTitles?.forEach { t -> t.takeIf { it.isNotBlank() }?.let { out.add(it) } }
        return out.toList()
    }

    private fun startKitsuSearch(categorySlug: String, categoryName: String) {
        KitsuApi.seedCategoryName(categorySlug, categoryName)
        startActivity(
            Intent(this, SearchActivity::class.java)
                .putExtra("type", (if (isAnime) SearchType.KITSU_ANIME else SearchType.KITSU).toAnilistString())
                .putExtra("category", categorySlug)
                .putExtra("categoryName", categoryName)
                .putExtra("search", true)
        )
    }
}
