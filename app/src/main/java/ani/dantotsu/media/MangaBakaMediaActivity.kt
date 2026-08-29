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
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityMangabakaMediaBinding
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
 * Standalone MangaBaka series screen — the search-result destination, mirroring [ComickMediaActivity].
 * Fetches a series by id and renders it through the shared [MangaBakaMediaRenderer] (the same code
 * [MangaBakaInfoFragment] uses).
 */
class MangaBakaMediaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "mangabaka_series_id"
    }

    private lateinit var binding: ActivityMangabakaMediaBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityMangabakaMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.mangaBakaMediaPages.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }
        binding.mangaBakaMediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight + 16f.px
        }
        binding.quickSettings.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight + 16f.px
        }
        binding.quickSettings.bindQuickSettings(this)
        binding.mangaBakaMediaClose.setOnClickListener { finish() }

        val seriesId = intent.getLongExtra(EXTRA_SERIES_ID, -1L)
            .takeIf { it > 0 }
            ?: run {
                val fromPath = intent.data?.pathSegments?.getOrNull(1)?.toLongOrNull()
                fromPath ?: run { finish(); return }
            }

        lifecycleScope.launch {
            val series = withContext(Dispatchers.IO) { MangaBakaApi.getSeries(seriesId) }
            if (series == null) {
                binding.mangaBakaMediaProgress.visibility = View.GONE
                Toast.makeText(this@MangaBakaMediaActivity, getString(R.string.mangabaka_no_data_title), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            setupHeader(series)
            setupSourceButtons(series)
            binding.mangaBakaMediaProgress.visibility = View.GONE
            binding.mangaBakaMediaInfoScroll.visibility = View.VISIBLE
            displaySeriesInfo(series)
        }
    }

    private fun setupHeader(series: MangaBakaApi.Series) {
        val coverUrl = series.cover?.fullUrl() ?: series.cover?.thumbUrl()
        if (coverUrl != null) {
            binding.mangaBakaMediaCover.loadImage(coverUrl)
            blurImage(binding.mangaBakaMediaBanner, coverUrl)
        }
        val displayTitle = series.displayTitle() ?: getString(R.string.unknown)
        binding.mangaBakaMediaTitle.text = displayTitle
        binding.mangaBakaMediaTitle.setOnLongClickListener {
            copyToClipboard(displayTitle)
            true
        }
        binding.mangaBakaMediaCover.setOnLongClickListener {
            ImageViewDialog.newInstance(this, getString(R.string.cover, displayTitle), coverUrl)
        }
        binding.mangaBakaMediaScore.text = series.rating?.let { "★ " + String.format(Locale.US, "%.1f", it / 10.0) } ?: ""
    }

    private fun setupSourceButtons(series: MangaBakaApi.Series) {
        val anilistId = series.source?.anilist?.id?.takeIf { it > 0 }
        val muId = series.source?.mangaUpdates?.id?.takeIf { it.isNotBlank() }
        var anyShown = false

        if (anilistId != null) {
            binding.mangaBakaMediaAnilistBtn.visibility = View.VISIBLE
            binding.mangaBakaMediaAnilistBtn.setText(R.string.comick_open_anilist)
            binding.mangaBakaMediaAnilistBtn.setOnClickListener {
                openOrCopyAnilistLink("https://anilist.co/manga/$anilistId")
            }
            anyShown = true
        }
        if (muId != null) {
            binding.mangaBakaMediaMuBtn.visibility = View.VISIBLE
            binding.mangaBakaMediaMuBtn.setText(R.string.comick_open_mangaupdates)
            binding.mangaBakaMediaMuBtn.setOnClickListener {
                val url = "https://www.mangaupdates.com/series/$muId"
                if (!openMangaUpdatesSeriesInApp(url)) openLinkInBrowser(url)
            }
            anyShown = true
        }
        binding.mangaBakaMediaSourceButtons.visibility = if (anyShown) View.VISIBLE else View.GONE
    }

    private fun displaySeriesInfo(series: MangaBakaApi.Series) {
        val info = FragmentMediaInfoBinding.inflate(layoutInflater)
        MangaBakaMediaRenderer.render(
            activity = this,
            scope = lifecycleScope,
            isAlive = { !isDestroyed },
            info = info,
            contentHost = binding.mangaBakaMediaContent,
            series = series,
            seriesId = series.id,
            nameInStats = false,
            onSearch = { genreSlug, genreName, tag -> startMangaBakaSearch(genreSlug, genreName, tag) },
        )
    }

    private fun startMangaBakaSearch(genreSlug: String?, genreName: String?, tag: String?) {
        val intent = Intent(this, SearchActivity::class.java)
            .putExtra("type", SearchType.MANGABAKA.toAnilistString())
        if (!genreSlug.isNullOrBlank()) {
            intent.putExtra("genre", genreSlug)
            if (!genreName.isNullOrBlank()) intent.putExtra("genreName", genreName)
        }
        if (!tag.isNullOrBlank()) intent.putExtra("tag", tag)
        if (!genreSlug.isNullOrBlank() || !tag.isNullOrBlank()) intent.putExtra("search", true)
        startActivity(intent)
    }
}
