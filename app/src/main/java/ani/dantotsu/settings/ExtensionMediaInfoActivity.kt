package ani.dantotsu.settings

import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.widget.CheckBox
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.FileUrl
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.mangaupdates.AniListQuickSearchDialogFragment
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.MangaUpdatesQuickSearchDialogFragment
import ani.dantotsu.databinding.ActivityExtensionMediaInfoBinding
import ani.dantotsu.databinding.ItemChapterGapBinding
import ani.dantotsu.databinding.ItemChapterListBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemEpisodeListBinding
import ani.dantotsu.databinding.ItemUrlBinding
import ani.dantotsu.bindScrollToTop
import ani.dantotsu.buildMarkwon
import ani.dantotsu.copyToClipboard
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaSingleton
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.Selected
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.media.manga.MangaChapter as MediaMangaChapter
import ani.dantotsu.media.manga.mangareader.MangaReaderActivity
import ani.dantotsu.media.novel.NovelChapterOpener
import ani.dantotsu.media.novel.novelreader.NovelReaderActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.ImageViewDialog
import nl.joery.animatedbottombar.AnimatedBottomBar
import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.parsers.novel.lnreader.LNReaderDates
import ani.dantotsu.parsers.novel.lnreader.LNReaderBook
import ani.dantotsu.parsers.novel.lnreader.LNReaderNovel
import ani.dantotsu.parsers.novel.lnreader.LNReaderParser
import ani.dantotsu.parsers.novel.lnreader.LNReaderPluginManager
import ani.dantotsu.parsers.novel.lnreader.LNReaderReadState
import ani.dantotsu.parsers.novel.lnreader.LNReaderSession
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.chip.Chip
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExtensionMediaInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TYPE = "type"
        const val EXTRA_LANG_INDEX = "lang"
        const val EXTRA_MANGA = "manga"
        const val EXTRA_ANIME = "anime"
        const val EXTRA_NOVEL = "novel"

        /**
         * How many novel chapters this screen lists.
         *
         * A novel can run to thousands, and this screen is for looking one up before adding it —
         * the media page is where a full run is read, and it pages with range chips.
         */
        private const val NOVEL_CHAPTER_PREVIEW = 100
    }

    private lateinit var binding: ActivityExtensionMediaInfoBinding
    private var manga: SManga? = null
    private var anime: SAnime? = null
    private var novel: ShowResponse? = null
    private var lnNovel: LNReaderNovel? = null
    private var novelParser: LNReaderParser? = null
    private var isManga: Boolean = true
    private val isNovel: Boolean get() = novel != null
    private var latestChapter: SChapter? = null
    private var latestEpisode: SEpisode? = null
    private var latestHasSub: Boolean = false
    private var latestHasDub: Boolean = false
    private var synopsisExpanded = false
    private var sourceHeaders: Map<String, String> = emptyMap()
    private lateinit var markwon: Markwon

    private var allChapters: List<SChapter> = emptyList()
    private var chaptersDataLoaded = false
    private var pkg: String? = null
    private var langIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityExtensionMediaInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        markwon = buildMarkwon(this, userInputContent = false, linkResolver = { openLinkInBrowser(it) })

        binding.extensionInfoBottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            height += navBarHeight
        }
        binding.extensionInfoBottomBar.setPadding(0, 0, 0, navBarHeight)
        binding.extensionInfoPages.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }

        binding.extensionInfoBack.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin += statusBarHeight
        }
        binding.extensionInfoBack.enableSettingsLongPress()
        binding.extensionInfoBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        @Suppress("DEPRECATION")
        manga = intent.getSerializableExtra(EXTRA_MANGA) as? SManga
        @Suppress("DEPRECATION")
        anime = intent.getSerializableExtra(EXTRA_ANIME) as? SAnime
        @Suppress("DEPRECATION")
        novel = intent.getSerializableExtra(EXTRA_NOVEL) as? ShowResponse
        isManga = when {
            anime != null -> false
            manga != null -> true
            novel != null -> false
            else -> intent.getStringExtra(EXTRA_TYPE) != ExtensionBrowseActivity.TYPE_ANIME
        }
        pkg = intent.getStringExtra(EXTRA_PKG)
        langIndex = intent.getIntExtra(EXTRA_LANG_INDEX, 0)
        sourceHeaders = if (pkg != null) computeSourceHeaders(pkg!!, langIndex) else emptyMap()

        // After the extras are read: which second tab (if any) belongs on the bar depends on
        // what was opened.
        setupBottomBar()

        bindInitial()
        configureSearchButtons()

        if (pkg != null) loadDetails(pkg!!, langIndex)
    }

    private var currentExtTabIndex = 0

    private fun setupBottomBar() {
        val navBar = binding.extensionInfoBottomBar
        val infoTab = navBar.createTab(R.drawable.ic_round_info_24, R.string.info, R.id.info)
        val chaptersTab = navBar.createTab(R.drawable.ic_round_import_contacts_24, R.string.read, R.id.read)
        navBar.addTab(infoTab)
        // Novels get the same second tab, listing the volume downloads the source offers rather
        // than a chapter list.
        if (isManga || isNovel) navBar.addTab(chaptersTab)
        navBar.selectTabAt(0)
        // Info is visible by default; chapters hidden
        binding.extensionInfoScroll.visibility = View.VISIBLE
        binding.extensionChaptersScroll.visibility = View.GONE
        binding.extensionChaptersScrollTop.bindScrollToTop(binding.extensionChaptersScroll)

        navBar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(lastIndex: Int, lastTab: AnimatedBottomBar.Tab?, newIndex: Int, newTab: AnimatedBottomBar.Tab) {
                if (newIndex == currentExtTabIndex) return
                slideExtToTab(from = currentExtTabIndex, to = newIndex)
                currentExtTabIndex = newIndex
            }
        })
    }

    private fun slideExtToTab(from: Int, to: Int) {
        val outView = if (from == 0) binding.extensionInfoScroll else binding.extensionChaptersScroll
        val inView  = if (to   == 0) binding.extensionInfoScroll else binding.extensionChaptersScroll
        val width = binding.extensionInfoPages.width.toFloat().takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.toFloat()
        val goRight = to > from
        inView.translationX = if (goRight) width else -width
        inView.visibility = View.VISIBLE
        outView.animate().translationX(if (goRight) -width else width).setDuration(280)
            .withEndAction { outView.visibility = View.GONE; outView.translationX = 0f }.start()
        inView.animate().translationX(0f).setDuration(280).start()
        // Scroll-to-top only applies to the chapters tab; hide it immediately when
        // navigating away so it doesn't linger over the info tab.
        if (to != 1) binding.extensionChaptersScrollTop.visibility = View.GONE
        // If switching to the chapters tab and data is already loaded, re-populate
        // using post{} so the view is measured at its final width first.
        if (to == 1 && chaptersDataLoaded) {
            inView.post { if (isNovel) populateNovelChapters() else populateChapterListWithChips() }
        }
    }

    private fun computeSourceHeaders(pkg: String, langIndex: Int): Map<String, String> {
        // Novel sources share one global request client and expose no per-source headers.
        if (isNovel) return emptyMap()
        val headers = if (isManga) {
            val mgr: MangaExtensionManager = Injekt.get()
            val ext = mgr.installedExtensionsFlow.value.find { it.pkgName == pkg }
            (ext?.sources?.getOrNull(langIndex) as? eu.kanade.tachiyomi.source.online.HttpSource)?.headers
        } else {
            val mgr: AnimeExtensionManager = Injekt.get()
            val ext = mgr.installedExtensionsFlow.value.find { it.pkgName == pkg }
            (ext?.sources?.getOrNull(langIndex) as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource)?.headers
        } ?: return emptyMap()
        val map = LinkedHashMap<String, String>(headers.size)
        headers.names().forEach { name -> headers[name]?.let { map[name] = it } }
        return map
    }

    private fun bindInitial() {
        val title = manga?.title ?: anime?.title ?: lnNovel?.name?.takeIf { it.isNotBlank() }
            ?: novel?.name ?: ""
        binding.extensionInfoTitle.text = title
        binding.extensionInfoTitle.setOnLongClickListener {
            copyToClipboard(title)
            true
        }
        val cover = manga?.thumbnail_url ?: anime?.thumbnail_url
            ?: lnNovel?.cover?.takeIf { it.isNotBlank() } ?: novel?.coverUrl?.url
        if (!cover.isNullOrBlank() && sourceHeaders.isNotEmpty() && (cover.startsWith("http://") || cover.startsWith("https://"))) {
            binding.extensionInfoCover.loadImage(FileUrl(cover, sourceHeaders))
        } else {
            binding.extensionInfoCover.loadImage(cover)
        }
        binding.extensionInfoCover.setOnLongClickListener {
            if (cover.isNullOrBlank()) return@setOnLongClickListener false
            // The FileUrl overload so the source's headers ride along — plenty of extension
            // CDNs 403 a bare request.
            ImageViewDialog.newInstance(
                getString(R.string.cover, title), FileUrl(cover, sourceHeaders), false, null
            ).show(supportFragmentManager, "image")
            true
        }
        blurImage(binding.extensionInfoBanner, cover, sourceHeaders)
        renderDetails()
    }

    private data class LoadedDetails(
        val details: Any?,
        val latest: Any?,
        val hasSub: Boolean = false,
        val hasDub: Boolean = false,
        val chapters: List<SChapter> = emptyList(),
    )

    /** Mirrors [ani.dantotsu.parsers.novel.DynamicNovelParser]'s volume matching, anchored at the end. */
    private val volumeSuffixRegex =
        Regex("""[\s\-–—,:]*\b(?:vol\.?|volume|v)\s*\d+(?:\.\d+)?\s*$""", RegexOption.IGNORE_CASE)

    private val subRegex = Regex("""\b(sub|subbed)\b""", RegexOption.IGNORE_CASE)
    private val dubRegex = Regex("""\b(dub|dubbed)\b""", RegexOption.IGNORE_CASE)

    private fun computeSubDub(list: List<SEpisode>, latest: SEpisode): Pair<Boolean, Boolean> {
        val siblings = if (latest.episode_number >= 0f) {
            list.filter { it.episode_number == latest.episode_number }
        } else listOf(latest)
        val pool = siblings.ifEmpty { listOf(latest) }
        val hasSub = pool.any {
            subRegex.containsMatchIn("${it.name} ${it.scanlator.orEmpty()}")
        }
        val hasDub = pool.any {
            dubRegex.containsMatchIn("${it.name} ${it.scanlator.orEmpty()}")
        }
        return hasSub to hasDub
    }

    private fun loadDetails(pkg: String, langIndex: Int) {
        synopsisExpanded = false
        binding.extensionInfoProgress.isVisible = true
        lifecycleScope.launch {
            val updated = runCatching {
                withContext(Dispatchers.IO) {
                    if (isNovel) {
                        val mgr: LNReaderPluginManager = Injekt.get()
                        val installed = mgr.installedPluginsFlow.value.find { it.id == pkg }
                            ?: return@withContext LoadedDetails(null, null)
                        val parser = LNReaderParser(applicationContext, installed)
                        novelParser = parser
                        val loaded = novel?.let { n -> parser.loadNovel(n.link) }
                        LoadedDetails(loaded, null)
                    } else if (isManga) {
                        val mgr: MangaExtensionManager = Injekt.get()
                        val ext = mgr.installedExtensionsFlow.value.find { it.pkgName == pkg }
                            ?: return@withContext LoadedDetails(null, null)
                        val source = ext.sources.getOrNull(langIndex) as? MangaSource
                            ?: return@withContext LoadedDetails(null, null)
                        // One combined call: sources on lib 1.6 answer only this, and for
                        // API-backed sites details and chapters come from the same response
                        // anyway. Older sources get a default that makes the two calls the app
                        // used to make itself.
                        val update = manga?.let { m ->
                            runCatching {
                                source.getMangaUpdate(m, emptyList(), true, true)
                            }.getOrNull()
                        }
                        val details = update?.manga
                        val list = update?.chapters.orEmpty()
                        val latest = list.maxByOrNull { it.chapter_number }
                            ?.takeIf { it.chapter_number >= 0f }
                            ?: list.firstOrNull()
                        LoadedDetails(details, latest, chapters = list)
                    } else {
                        val mgr: AnimeExtensionManager = Injekt.get()
                        val ext = mgr.installedExtensionsFlow.value.find { it.pkgName == pkg }
                            ?: return@withContext LoadedDetails(null, null)
                        val source = ext.sources.getOrNull(langIndex) as? AnimeSource
                            ?: return@withContext LoadedDetails(null, null)
                        val details = anime?.let { source.getAnimeDetails(it) }
                        val listResult = anime?.let { a ->
                            runCatching { source.getEpisodeList(a) }.getOrNull()
                        }.orEmpty()
                        val latest = listResult.maxByOrNull { it.episode_number }
                            ?.takeIf { it.episode_number >= 0f }
                            ?: listResult.firstOrNull()
                        val (hasSub, hasDub) =
                            latest?.let { computeSubDub(listResult, it) } ?: (false to false)
                        LoadedDetails(details, latest, hasSub, hasDub)
                    }
                }
            }
            binding.extensionInfoProgress.isVisible = false
            if (updated.isFailure) {
                Logger.log(updated.exceptionOrNull() ?: Exception("details failed"))
                return@launch
            }
            val result = updated.getOrNull() ?: LoadedDetails(null, null)
            when (val d = result.details) {
                is SManga -> {
                    manga?.copyFrom(d)
                    if (manga?.title.isNullOrBlank()) manga?.title = d.title
                }
                is SAnime -> {
                    anime?.copyFrom(d)
                    if (anime?.title.isNullOrBlank()) anime?.title = d.title
                }
                is LNReaderNovel -> {
                    lnNovel = d
                    // The search result carried only a name and a cover; the loaded novel is
                    // where a synopsis, author and a better title/cover come from.
                    bindInitial()
                }
            }
            latestChapter = result.latest as? SChapter
            latestEpisode = result.latest as? SEpisode
            latestHasSub = result.hasSub
            latestHasDub = result.hasDub
            allChapters = result.chapters
            chaptersDataLoaded = true
            renderDetails()
            renderLatest()
            renderChapterList()
        }
    }

    private fun renderDetails() {
        val author = manga?.author ?: anime?.author ?: lnNovel?.author
        val artist = manga?.artist ?: anime?.artist ?: lnNovel?.artist
        val description = manga?.description ?: anime?.description ?: lnNovel?.summary
        val genres = manga?.getGenres() ?: anime?.getGenres()
            ?: lnNovel?.genreList()?.takeIf { it.isNotEmpty() }
        val status = manga?.status ?: anime?.status ?: SManga.UNKNOWN
        // A plugin reports status as free text rather than one of SManga's constants, so it is
        // shown as written instead of being mapped onto a set of labels it was never drawn from.
        val novelStatus = lnNovel?.status?.takeIf { it.isNotBlank() }

        val authorLine = listOfNotNull(
            author?.takeIf { it.isNotBlank() },
            artist?.takeIf { it.isNotBlank() && it != author }
        ).joinToString(", ")
        binding.extensionInfoAuthor.text = authorLine
        binding.extensionInfoAuthor.isVisible = authorLine.isNotBlank()

        val statusText = novelStatus ?: statusLabel(status)
        binding.extensionInfoStatus.text = statusText
        binding.extensionInfoStatus.isVisible = statusText.isNotBlank()

        val showSynopsis = !description.isNullOrBlank()
        binding.extensionInfoSynopsisTitle.isVisible = showSynopsis
        binding.extensionInfoSynopsis.isVisible = showSynopsis
        if (showSynopsis) {
            markwon.setMarkdown(binding.extensionInfoSynopsis, description!!)
            binding.extensionInfoSynopsis.movementMethod =
                android.text.method.LinkMovementMethod.getInstance()
            binding.extensionInfoSynopsis.maxLines = if (synopsisExpanded) Int.MAX_VALUE else 4
            binding.extensionInfoSynopsis.ellipsize =
                if (synopsisExpanded) null else android.text.TextUtils.TruncateAt.END
            binding.extensionInfoSynopsis.setOnClickListener {
                synopsisExpanded = !synopsisExpanded
                binding.extensionInfoSynopsis.maxLines = if (synopsisExpanded) Int.MAX_VALUE else 4
                binding.extensionInfoSynopsis.ellipsize =
                    if (synopsisExpanded) null else android.text.TextUtils.TruncateAt.END
            }
        }

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

    private fun renderLatest() {
        binding.extensionInfoLatestContainer.removeAllViews()
        val chapter = latestChapter
        val episode = latestEpisode
        when {
            chapter != null -> {
                val b = ItemChapterListBinding.inflate(
                    layoutInflater, binding.extensionInfoLatestContainer, true
                )
                b.itemDownload.isVisible = false
                b.itemChapterBrowser.isVisible = false
                b.itemEpisodeViewed.isVisible = false

                b.itemChapterNumber.text = chapter.name
                val dateText = formatDate(chapter.date_upload)
                val scan = chapter.scanlator?.takeIf { it.isNotBlank() }?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
                val hasDate = dateText.isNotBlank()
                val hasScan = !scan.isNullOrBlank()
                b.itemChapterDateLayout.isVisible = hasDate || hasScan
                b.itemChapterDate.isVisible = hasDate
                b.itemChapterDate.text = dateText
                b.itemChapterScan.isVisible = hasScan
                b.itemChapterScan.text = scan ?: ""
                b.itemChapterDateDivider.isVisible = hasDate && hasScan

                binding.extensionInfoLatestTitle.setText(R.string.latest_chapter)
                binding.extensionInfoLatestTitle.isVisible = true
                binding.extensionInfoLatestContainer.isVisible = true
            }
            episode != null -> {
                val b = ItemEpisodeListBinding.inflate(
                    layoutInflater, binding.extensionInfoLatestContainer, true
                )
                b.itemDownload.isVisible = false
                b.itemEpisodeBrowser.isVisible = false
                b.itemEpisodeViewed.isVisible = false
                (b.itemMediaImage.parent as? View)?.isVisible = false

                val effectiveNumber = episode.episode_number
                    .takeIf { it >= 0f }
                    ?: MediaNameAdapter.findEpisodeNumber(episode.name)
                    ?: -1f
                val numberText = when {
                    effectiveNumber < 0f -> ""
                    effectiveNumber == effectiveNumber.toInt().toFloat() ->
                        effectiveNumber.toInt().toString()
                    else -> effectiveNumber.toString()
                }

                val stripped = MediaNameAdapter.removeEpisodeNumberCompletely(episode.name).trim()
                b.itemEpisodeTitle.text = when {
                    numberText.isNotEmpty() && stripped.isNotBlank() ->
                        "Episode $numberText: $stripped"
                    numberText.isNotEmpty() -> "Episode $numberText"
                    stripped.isNotBlank() -> stripped
                    else -> episode.name
                }
                b.itemEpisodeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                b.itemEpisodeDesc.isVisible = false

                val titleContainer = b.itemEpisodeTitle.parent as? LinearLayout
                val density = resources.displayMetrics.density
                val startPadPx = (16 * density).toInt()
                val verticalPadPx = (10 * density).toInt()
                titleContainer?.apply {
                    minimumHeight = 0
                    setPadding(startPadPx, verticalPadPx, paddingRight, verticalPadPx)
                    (layoutParams as? LinearLayout.LayoutParams)?.apply {
                        topMargin = 0
                        bottomMargin = 0
                        layoutParams = this
                    }
                }
                addEpisodeMeta(titleContainer, episode)

                binding.extensionInfoLatestTitle.setText(R.string.latest_episode)
                binding.extensionInfoLatestTitle.isVisible = true
                binding.extensionInfoLatestContainer.isVisible = true
            }
            else -> {
                binding.extensionInfoLatestTitle.isVisible = false
                binding.extensionInfoLatestContainer.isVisible = false
            }
        }
    }

    private fun renderChapterList() {
        if (isNovel) {
            binding.extensionChaptersScroll.post { populateNovelChapters() }
            return
        }
        if (!isManga) return
        // post{} so chips are built after the view has been laid out at real width
        binding.extensionChaptersScroll.post { populateChapterListWithChips() }
    }

    /**
     * The novel chapter list.
     *
     * The same shape as the manga chapter list on this screen: tapping a chapter reads it, and
     * there is no download button, because a download has to be filed under a tracked media. This
     * screen is source browsing — the entry has not been matched to anything yet.
     */
    private fun populateNovelChapters() {
        binding.extensionChaptersList.removeAllViews()
        binding.extensionChaptersChipGroup.removeAllViews()
        binding.extensionChaptersChipScroll.isVisible = false

        val details = lnNovel
        val chapters = details?.chapters.orEmpty()
        binding.extensionChaptersEmpty.isVisible = chapters.isEmpty()
        binding.extensionChaptersEmpty.setText(R.string.no_chapter)
        binding.extensionChaptersHeader.isVisible = chapters.isNotEmpty()
        binding.extensionChaptersHeader.text =
            resources.getQuantityString(R.plurals.novel_chapter_count, chapters.size, chapters.size)
        if (details == null) return

        // Capped rather than paged: this screen is for looking a novel up before adding it, and
        // the media page is where a full run gets read.
        chapters.take(NOVEL_CHAPTER_PREVIEW).forEachIndexed { index, chapter ->
            val b = ItemChapterListBinding.inflate(
                layoutInflater, binding.extensionChaptersList, true
            )
            b.itemChapterNumber.text = chapter.name
            b.itemChapterTitle.isVisible = false
            b.itemEpisodeViewed.isVisible = false
            b.itemChapterBrowser.isVisible = false
            b.itemDownload.isVisible = false

            val date = LNReaderDates.format(chapter.releaseTime)
            b.itemChapterDateLayout.isVisible = date.isNotBlank()
            b.itemChapterDate.isVisible = date.isNotBlank()
            b.itemChapterDate.text = date
            b.itemChapterScan.isVisible = false
            b.itemChapterDateDivider.isVisible = false

            b.root.setOnClickListener { openNovelChapter(details, index) }
        }
    }

    /** Fetches a chapter, packages it as EPUB and opens the reader. */
    private fun openNovelChapter(details: LNReaderNovel, index: Int) {
        val parser = novelParser ?: return
        // The same loading sheet the media page uses. No tracking question here: browsing a source
        // is not reading a media, so there is nothing a list could be told about it.
        NovelChapterOpener.open(this, parser, details, index, media = null)
    }

    private val nonSequentialKeywords = setOf(
        "extra", "omake", "special", "side story", "prologue", "epilogue",
        "afterword", "author", "bonus", "cover story", "gaiden", "interlude"
    )

    private fun resolveChapterNum(sChap: SChapter): Float? {
        val n = sChap.chapter_number
        return if (n > 0f) n else MediaNameAdapter.findChapterNumber(sChap.name)
    }


    private fun chapterLimit(total: Int): Int {
        val d = total / 10.0
        return when { d < 25 -> 25; d < 50 -> 50; else -> 100 }
    }

    /**
     * The canonical chapter order, matching how AniList/MangaUpdates media resolve & display
     * chapters: the source's [HttpSource.getChapterList] order (newest-first) reversed to oldest-
     * first, with no re-sorting by parsed number. ([AniyomiAdapter.loadChapters] reverses the same
     * way before [BaseSources.loadChapters] keys the map by uniqueNumber.)
     *
     * Used for BOTH the on-screen chapter list and the map handed to the reader, so the two stay in
     * lock-step — including how equal-numbered duplicates (e.g. two "Chapter 0") are ordered.
     */
    private fun chaptersInReadingOrder(): List<SChapter> = allChapters.reversed()

    private fun populateChapterListWithChips() {
        binding.extensionChaptersList.removeAllViews()
        binding.extensionChaptersChipGroup.removeAllViews()

        if (allChapters.isEmpty()) {
            binding.extensionChaptersChipScroll.visibility = View.GONE
            binding.extensionChaptersHeader.isVisible = false
            binding.extensionChaptersEmpty.isVisible = true
            return
        }

        binding.extensionChaptersEmpty.isVisible = false

        val chapters = chaptersInReadingOrder()
        val total = chapters.size

        val missing = computeMissingChapters(chapters)
        binding.extensionChaptersHeader.isVisible = true
        binding.extensionChaptersHeader.text = buildChaptersHeader(missing)
        val limit = chapterLimit(total)
        if (total > limit) {
            buildExtensionChipGroups(chapters, limit)
        } else {
            binding.extensionChaptersChipScroll.visibility = View.GONE
            showExtensionChapterRange(chapters, 0, total - 1)
        }
    }

    private fun buildExtensionChipGroups(chapters: List<SChapter>, limit: Int) {
        binding.extensionChaptersChipScroll.visibility = View.VISIBLE
        val total = chapters.size
        val groupCount = (total + limit - 1) / limit
        var firstChip: Chip? = null

        for (groupIdx in 0 until groupCount) {
            val startIdx = groupIdx * limit
            val endIdx = minOf(startIdx + limit - 1, total - 1)

            val startNum = resolveChapterNum(chapters[startIdx])
            val endNum = resolveChapterNum(chapters[endIdx])
            val chipText = when {
                startNum != null && endNum != null -> {
                    val lo = if (startNum % 1f == 0f) startNum.toInt().toString() else startNum.toString()
                    val hi = if (endNum % 1f == 0f) endNum.toInt().toString() else endNum.toString()
                    "Ch.$lo - Ch.$hi"
                }
                else -> "${startIdx + 1}-${endIdx + 1}"
            }

            val chip = ItemChipBinding.inflate(layoutInflater, binding.extensionChaptersChipGroup, false).root
            chip.isCheckable = true
            chip.text = chipText
            chip.setTextColor(androidx.core.content.ContextCompat.getColorStateList(this, R.color.chip_text_color))
            chip.setOnClickListener {
                chip.isChecked = true
                showExtensionChapterRange(chapters, startIdx, endIdx)
            }
            binding.extensionChaptersChipGroup.addView(chip)
            if (groupIdx == 0) {
                chip.isChecked = true
                firstChip = chip
            }
        }

        firstChip?.let { showExtensionChapterRange(chapters, 0, minOf(limit - 1, chapters.size - 1)) }
    }

    private fun showExtensionChapterRange(chapters: List<SChapter>, startIdx: Int, endIdx: Int) {
        binding.extensionChaptersList.removeAllViews()
        val rangeChapters = chapters.subList(startIdx, endIdx + 1)

        fun addChapterView(sChap: SChapter) {
            val b = ItemChapterListBinding.inflate(layoutInflater, binding.extensionChaptersList, false)
            b.itemChapterDateLayout.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            b.itemDownload.isVisible = false
            b.itemEpisodeViewed.isVisible = false
            b.itemChapterBrowser.isVisible = false
            b.itemChapterNumber.text = sChap.name
            val dateText = formatDate(sChap.date_upload)
            val scan = sChap.scanlator?.takeIf { it.isNotBlank() }?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
            val hasDate = dateText.isNotBlank()
            val hasScan = !scan.isNullOrBlank()
            b.itemChapterDateLayout.isVisible = hasDate || hasScan
            b.itemChapterDate.isVisible = hasDate
            b.itemChapterDate.text = dateText
            b.itemChapterScan.isVisible = hasScan
            b.itemChapterScan.text = scan ?: ""
            b.itemChapterDateDivider.isVisible = hasDate && hasScan
            b.root.setOnClickListener { openChapter(sChap) }
            binding.extensionChaptersList.addView(b.root)
        }

        fun inflateGapView(count: Int) = ItemChapterGapBinding.inflate(
            layoutInflater, binding.extensionChaptersList, false
        ).also { b ->
            b.itemChapterGapText.text = if (count == 1)
                getString(R.string.chapter_missing_single)
            else
                getString(R.string.chapters_missing, count)
        }.root

        for (i in rangeChapters.indices) {
            addChapterView(rangeChapters[i])
            if (i < rangeChapters.size - 1) {
                val currIsNonSeq = nonSequentialKeywords.any { rangeChapters[i].name.lowercase().contains(it) }
                val nextIsNonSeq = nonSequentialKeywords.any { rangeChapters[i + 1].name.lowercase().contains(it) }
                if (!currIsNonSeq && !nextIsNonSeq) {
                    val c = resolveChapterNum(rangeChapters[i])
                    val n = resolveChapterNum(rangeChapters[i + 1])
                    if (c != null && n != null) {
                        val missing = maxOf(c, n).toInt() - minOf(c, n).toInt() - 1
                        if (missing > 0) binding.extensionChaptersList.addView(inflateGapView(missing))
                    }
                }
            }
        }
    }

    private fun openChapter(sChapter: SChapter) {
        val warningDismissed = PrefManager.getCustomVal("ext_reader_no_tracking_warning_dismissed", false)
        if (!warningDismissed) {
            val dialogView = layoutInflater.inflate(R.layout.item_custom_dialog, null)
            val checkbox = dialogView.findViewById<CheckBox>(R.id.dialog_checkbox)
            checkbox.text = getString(R.string.ext_reader_no_tracking_dismiss)
            customAlertDialog().apply {
                setTitle(R.string.ext_reader_no_tracking_title)
                setMessage(getString(R.string.ext_reader_no_tracking_message))
                setCustomView(dialogView)
                setPosButton(R.string.proceed) {
                    if (checkbox.isChecked) {
                        PrefManager.setCustomVal("ext_reader_no_tracking_warning_dismissed", true)
                    }
                    doOpenChapter(sChapter)
                }
                setNegButton(R.string.cancel)
                show()
            }
            return
        }
        doOpenChapter(sChapter)
    }

    private fun doOpenChapter(sChapter: SChapter) {
        val currentPkg = pkg ?: return
        val currentLangIndex = langIndex
        binding.extensionInfoProgress.isVisible = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val mgr: MangaExtensionManager = Injekt.get()
                    val ext = mgr.installedExtensionsFlow.value.find { it.pkgName == currentPkg }
                        ?: return@runCatching null
                    val parser = DynamicMangaParser(ext)
                    parser.sourceLanguage = currentLangIndex
                    MediaSingleton.extensionParser = parser

                    val selectedChapter = sChapterToMediaMangaChapter(sChapter)
                    val images = parser.loadImages(sChapter.url, sChapter)
                    if (images.isEmpty()) return@runCatching null
                    selectedChapter.addImages(images)

                    val chaptersMap = LinkedHashMap<String, MediaMangaChapter>()
                    for (sch in chaptersInReadingOrder()) {
                        val mc = sChapterToMediaMangaChapter(sch)
                        chaptersMap[mc.uniqueNumber()] = if (sch.url == sChapter.url) selectedChapter else mc
                    }
                    // A duplicate with the same uniqueNumber could have overwritten selectedChapter
                    // in the loop above; re-insert it so the reader always resolves the right chapter.
                    chaptersMap[selectedChapter.uniqueNumber()] = selectedChapter

                    val title = manga?.title ?: ""
                    val coverUrl = manga?.thumbnail_url
                    Media(
                        id = -1,
                        name = title,
                        nameRomaji = title,
                        userPreferredName = title,
                        cover = coverUrl,
                        isAdult = ext.isNsfw,
                        manga = Manga(
                            chapters = chaptersMap,
                            selectedChapter = selectedChapter,
                        ),
                        selected = Selected(sourceIndex = 0, langIndex = currentLangIndex),
                    )
                }.getOrNull()
            }
            binding.extensionInfoProgress.isVisible = false
            if (result != null) {
                MediaSingleton.media = result
                startActivity(Intent(this@ExtensionMediaInfoActivity, MangaReaderActivity::class.java))
            } else {
                snackString(getString(R.string.failed_to_load))
            }
        }
    }

    private fun sChapterToMediaMangaChapter(sChapter: SChapter) = MediaMangaChapter(
        number = sChapter.name,
        link = sChapter.url,
        sChapter = sChapter,
        scanlator = sChapter.scanlator,
        date = sChapter.date_upload,
    )

    private fun addEpisodeMeta(container: LinearLayout?, episode: SEpisode) {
        container ?: return
        val parts = buildList {
            val dateText = formatDate(episode.date_upload)
            if (dateText.isNotBlank()) add(dateText)
            val subDub = when {
                latestHasSub && latestHasDub -> "Sub & Dub"
                latestHasSub -> "Sub"
                latestHasDub -> "Dub"
                else -> null
            }
            if (subDub != null) add(subDub)
        }
        if (parts.isEmpty()) return
        val poppinsBold = ResourcesCompat.getFont(this, R.font.poppins_bold)
        val marginPx = (3 * resources.displayMetrics.density).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * resources.displayMetrics.density).toInt()
                marginStart = (4 * resources.displayMetrics.density).toInt()
            }
        }
        parts.forEachIndexed { idx, text ->
            if (idx > 0) row.addView(makeMetaDivider(poppinsBold, marginPx))
            row.addView(makeMetaLabel(text, poppinsBold, marginPx))
        }
        container.addView(row)
    }

    private fun makeMetaLabel(text: String, font: Typeface?, marginPx: Int) = TextView(this).apply {
        this.text = text
        alpha = 0.6f
        typeface = font
        maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = marginPx }
    }

    private fun makeMetaDivider(font: Typeface?, marginPx: Int) = TextView(this).apply {
        text = "•"
        alpha = 0.6f
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = marginPx }
    }

    private fun computeMissingChapters(chapters: List<SChapter>): Int {
        val nonSeq = nonSequentialKeywords
        var missing = 0
        var prevNum: Int? = null
        for (ch in chapters) {
            if (nonSeq.any { ch.name.lowercase().contains(it) }) { prevNum = null; continue }
            val n = resolveChapterNum(ch)?.toInt() ?: continue
            val prev = prevNum
            if (prev != null && n > prev + 1) missing += n - prev - 1
            prevNum = n
        }
        return missing
    }

    private fun buildChaptersHeader(missing: Int): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(getString(R.string.chaps))
        if (missing > 0) {
            ssb.append("\n")
            val start = ssb.length
            val label = if (missing == 1) getString(R.string.chapter_missing_single)
                        else getString(R.string.chapters_missing, missing)
            ssb.append(label)
            ssb.setSpan(RelativeSizeSpan(0.68f), start, ssb.length, 0)
        }
        return ssb
    }

    private fun formatDate(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        // Some sources return seconds instead of milliseconds. If the value is
        // too small to represent a modern date in ms (~1e12 for 2001+), treat
        // it as seconds and normalize.
        val ms = if (timestamp < 100_000_000_000L) timestamp * 1000 else timestamp
        val targetDate = Date(ms)
        val currentDate = Date()
        val difference = currentDate.time - targetDate.time
        return when (val daysDifference = difference / (1000 * 60 * 60 * 24)) {
            0L -> {
                val hoursDifference = difference / (1000 * 60 * 60)
                val minutesDifference = (difference / (1000 * 60)) % 60
                when {
                    hoursDifference > 0 -> "$hoursDifference hour${if (hoursDifference > 1) "s" else ""} ago"
                    minutesDifference > 0 -> "$minutesDifference minute${if (minutesDifference > 1) "s" else ""} ago"
                    else -> "Just now"
                }
            }
            1L -> "1 day ago"
            in 2..6 -> "$daysDifference days ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(targetDate)
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
            val type = when {
                isNovel -> AniListQuickSearchDialogFragment.TYPE_NOVEL
                isManga -> AniListQuickSearchDialogFragment.TYPE_MANGA
                else -> AniListQuickSearchDialogFragment.TYPE_ANIME
            }
            AniListQuickSearchDialogFragment
                .newInstance(
                    titles = ArrayList(titles),
                    type = type,
                    extensionPkg = pkg,
                    extensionLangIndex = langIndex,
                    sManga = manga,
                    sAnime = anime,
                    novel = novel,
                )
                .show(supportFragmentManager, "ext_anilist_quick_search")
        }

        // MangaUpdates catalogues light novels alongside manga, so a plugin entry can be matched
        // there as readily as an Aniyomi one; only anime has nothing to look up.
        binding.extensionInfoSearchMu.isVisible =
            (isManga || isNovel) && MangaUpdates.token != null
        binding.extensionInfoSearchMu.setOnClickListener {
            val titles = collectTitles()
            if (titles.isEmpty()) return@setOnClickListener
            MangaUpdatesQuickSearchDialogFragment
                .newInstance(
                    titles = ArrayList(titles),
                    extensionPkg = pkg,
                    extensionLangIndex = langIndex,
                    sManga = manga,
                    novel = novel,
                )
                .show(supportFragmentManager, "ext_mu_quick_search")
        }
    }

    private fun collectTitles(): List<String> {
        val list = mutableListOf<String>()
        val novelTitle = lnNovel?.name?.takeIf { it.isNotBlank() } ?: novel?.name
        (manga?.title ?: anime?.title ?: novelTitle)?.takeIf { it.isNotBlank() }
            ?.let { list.add(it.trim()) }
        // A novel entry is usually one volume ("… Vol. 3"), but the AniList entry it should link
        // to is the series, so offer the volume-stripped title as a second query.
        if (novelTitle != null) {
            novelTitle.replace(volumeSuffixRegex, "").trim().trim('-', '–', '—', ',', ':')
                .takeIf { it.isNotBlank() }?.let { list.add(it) }
        }
        val description = manga?.description ?: anime?.description ?: lnNovel?.summary
        if (!description.isNullOrBlank()) list.addAll(extractAlternateTitles(description))
        return list
            .map { it.trim().trim('"', '\'', '“', '”', '‘', '’') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun extractAlternateTitles(description: String): List<String> {
        val labelPattern = Regex(
            "(?i)^\\s*(?:alt(?:ernative|\\.)?|other|also\\s+known\\s+as|aka|synonyms?|associated\\s+names?)" +
                "\\s*(?:title|titles|name|names|title\\(s\\)|name\\(s\\))?\\s*[:：\\-–—]\\s*(.*)$"
        )
        val bulletPattern = Regex("^\\s*(?:[-*•·・‣▪►–—]|\\d+[.)])\\s*(.+?)\\s*$")
        val results = mutableListOf<String>()
        val lines = description.split('\n')
        var i = 0
        while (i < lines.size) {
            val match = labelPattern.matchEntire(lines[i])
            if (match == null) { i++; continue }
            val inline = match.groupValues[1].trim()
            if (inline.isNotEmpty()) {
                splitInlineNames(inline).forEach { results.add(it) }
            }
            var j = i + 1
            while (j < lines.size) {
                val line = lines[j]
                if (line.isBlank()) { j++; continue }
                val bullet = bulletPattern.matchEntire(line)
                if (bullet != null) {
                    val name = bullet.groupValues[1].trim()
                    if (name.isNotBlank()) results.add(name)
                    j++
                } else break
            }
            i = j
        }
        return results.filter { it.length in 2..120 }
    }

    private fun splitInlineNames(payload: String): List<String> {
        val strong = charArrayOf(';', '|', '/', '・', '·')
        val separators = if (strong.any { payload.contains(it) }) strong else charArrayOf(',')
        return payload.split(*separators)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
