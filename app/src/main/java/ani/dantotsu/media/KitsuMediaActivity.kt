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
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.kitsu.KitsuApi
import ani.dantotsu.connections.mangaupdates.AniListQuickSearchDialogFragment
import ani.dantotsu.connections.mangaupdates.MangaUpdatesQuickSearchDialogFragment
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityKitsuMediaBinding
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
    }

    private lateinit var binding: ActivityKitsuMediaBinding
    private var isAnime = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityKitsuMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

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
        binding.kitsuMediaClose.setOnClickListener { finish() }

        isAnime = intent.getBooleanExtra(EXTRA_IS_ANIME, false)
        val mediaId = intent.getStringExtra(EXTRA_MEDIA_ID)
            ?: intent.data?.pathSegments?.getOrNull(1)
            ?: run { finish(); return }

        lifecycleScope.launch {
            val full = withContext(Dispatchers.IO) { KitsuApi.getMediaFull(isAnime, mediaId) }
            if (full == null) {
                binding.kitsuMediaProgress.visibility = View.GONE
                Toast.makeText(this@KitsuMediaActivity, getString(R.string.kitsu_no_data_title), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            setupHeader(full)
            setupSourceButtons(full)
            binding.kitsuMediaProgress.visibility = View.GONE
            binding.kitsuMediaInfoScroll.visibility = View.VISIBLE

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
