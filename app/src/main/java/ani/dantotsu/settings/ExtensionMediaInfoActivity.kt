package ani.dantotsu.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.blurImage
import ani.dantotsu.connections.mangaupdates.AniListQuickSearchDialogFragment
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.MangaUpdatesQuickSearchDialogFragment
import ani.dantotsu.databinding.ActivityExtensionMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionMediaInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TYPE = "type"
        const val EXTRA_LANG_INDEX = "lang"
        const val EXTRA_MANGA = "manga"
        const val EXTRA_ANIME = "anime"
    }

    private lateinit var binding: ActivityExtensionMediaInfoBinding
    private var manga: SManga? = null
    private var anime: SAnime? = null
    private var isManga: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityExtensionMediaInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.extensionInfoRoot.setPadding(0, 0, 0, navBarHeight)

        binding.extensionInfoBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        @Suppress("DEPRECATION")
        manga = intent.getSerializableExtra(EXTRA_MANGA) as? SManga
        @Suppress("DEPRECATION")
        anime = intent.getSerializableExtra(EXTRA_ANIME) as? SAnime
        isManga = when {
            anime != null -> false
            manga != null -> true
            else -> intent.getStringExtra(EXTRA_TYPE) != ExtensionBrowseActivity.TYPE_ANIME
        }
        val pkg = intent.getStringExtra(EXTRA_PKG)
        val langIndex = intent.getIntExtra(EXTRA_LANG_INDEX, 0)

        bindInitial()
        configureSearchButtons()

        if (pkg != null) loadDetails(pkg, langIndex)
    }

    private fun bindInitial() {
        val title = manga?.title ?: anime?.title ?: ""
        binding.extensionInfoTitle.text = title
        val cover = manga?.thumbnail_url ?: anime?.thumbnail_url
        binding.extensionInfoCover.loadImage(cover)
        blurImage(binding.extensionInfoBanner, cover)
        renderDetails()
    }

    private fun loadDetails(pkg: String, langIndex: Int) {
        binding.extensionInfoProgress.isVisible = true
        lifecycleScope.launch {
            val updated = runCatching {
                withContext(Dispatchers.IO) {
                    if (isManga) {
                        val mgr: MangaExtensionManager = Injekt.get()
                        val ext = mgr.installedExtensionsFlow.value.find { it.pkgName == pkg }
                            ?: return@withContext null
                        val source = ext.sources.getOrNull(langIndex) as? MangaSource
                            ?: return@withContext null
                        manga?.let { source.getMangaDetails(it) }
                    } else {
                        val mgr: AnimeExtensionManager = Injekt.get()
                        val ext = mgr.installedExtensionsFlow.value.find { it.pkgName == pkg }
                            ?: return@withContext null
                        val source = ext.sources.getOrNull(langIndex) as? AnimeSource
                            ?: return@withContext null
                        anime?.let { source.getAnimeDetails(it) }
                    }
                }
            }
            binding.extensionInfoProgress.isVisible = false
            if (updated.isFailure) {
                Logger.log(updated.exceptionOrNull() ?: Exception("details failed"))
                return@launch
            }
            when (val result = updated.getOrNull()) {
                is SManga -> {
                    manga?.copyFrom(result)
                    if (manga?.title.isNullOrBlank()) manga?.title = result.title
                }
                is SAnime -> {
                    anime?.copyFrom(result)
                    if (anime?.title.isNullOrBlank()) anime?.title = result.title
                }
            }
            renderDetails()
        }
    }

    private fun renderDetails() {
        val author = manga?.author ?: anime?.author
        val artist = manga?.artist ?: anime?.artist
        val description = manga?.description ?: anime?.description
        val genres = manga?.getGenres() ?: anime?.getGenres()
        val status = manga?.status ?: anime?.status ?: SManga.UNKNOWN

        val authorLine = listOfNotNull(
            author?.takeIf { it.isNotBlank() },
            artist?.takeIf { it.isNotBlank() && it != author }
        ).joinToString(", ")
        binding.extensionInfoAuthor.text = authorLine
        binding.extensionInfoAuthor.isVisible = authorLine.isNotBlank()

        binding.extensionInfoStatus.text = statusLabel(status)
        binding.extensionInfoStatus.isVisible = status != SManga.UNKNOWN

        val showSynopsis = !description.isNullOrBlank()
        binding.extensionInfoSynopsisTitle.isVisible = showSynopsis
        binding.extensionInfoSynopsis.isVisible = showSynopsis
        binding.extensionInfoSynopsis.text = description ?: ""

        binding.extensionInfoGenresChips.removeAllViews()
        val showGenres = !genres.isNullOrEmpty()
        binding.extensionInfoGenresTitle.isVisible = showGenres
        binding.extensionInfoGenresChips.isVisible = showGenres
        genres?.forEach { g ->
            val chip = ItemChipBinding.inflate(layoutInflater,
                binding.extensionInfoGenresChips, false).root
            chip.text = g
            chip.isCheckable = false
            chip.isClickable = false
            binding.extensionInfoGenresChips.addView(chip)
        }
    }

    private fun statusLabel(status: Int): String = when (status) {
        SManga.ONGOING -> "Ongoing"
        SManga.COMPLETED -> "Completed"
        SManga.LICENSED -> "Licensed"
        SManga.PUBLISHING_FINISHED -> "Publishing Finished"
        SManga.CANCELLED -> "Cancelled"
        SManga.ON_HIATUS -> "On Hiatus"
        else -> ""
    }

    private fun configureSearchButtons() {
        binding.extensionInfoSearchAnilist.setOnClickListener {
            val titles = collectTitles()
            if (titles.isEmpty()) return@setOnClickListener
            val type = if (isManga) AniListQuickSearchDialogFragment.TYPE_MANGA
            else AniListQuickSearchDialogFragment.TYPE_ANIME
            AniListQuickSearchDialogFragment
                .newInstance(ArrayList(titles), type = type)
                .show(supportFragmentManager, "ext_anilist_quick_search")
        }

        val muVisible = isManga && MangaUpdates.token != null
        binding.extensionInfoSearchMu.isVisible = muVisible
        binding.extensionInfoSearchMu.setOnClickListener {
            val titles = collectTitles()
            if (titles.isEmpty()) return@setOnClickListener
            MangaUpdatesQuickSearchDialogFragment
                .newInstance(ArrayList(titles))
                .show(supportFragmentManager, "ext_mu_quick_search")
        }
    }

    private fun collectTitles(): List<String> {
        val list = mutableListOf<String>()
        (manga?.title ?: anime?.title)?.takeIf { it.isNotBlank() }?.let { list.add(it.trim()) }
        return list.distinctBy { it.lowercase() }
    }
}
