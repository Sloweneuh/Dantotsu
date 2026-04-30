package ani.dantotsu.settings

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.FileUrl
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.mangaupdates.AniListQuickSearchDialogFragment
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.MangaUpdatesQuickSearchDialogFragment
import ani.dantotsu.databinding.ActivityExtensionMediaInfoBinding
import ani.dantotsu.databinding.ItemChapterListBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemEpisodeListBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.navBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
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
    }

    private lateinit var binding: ActivityExtensionMediaInfoBinding
    private var manga: SManga? = null
    private var anime: SAnime? = null
    private var isManga: Boolean = true
    private var latestChapter: SChapter? = null
    private var latestEpisode: SEpisode? = null
    private var latestHasSub: Boolean = false
    private var latestHasDub: Boolean = false
    private var synopsisExpanded = false
    private var sourceHeaders: Map<String, String> = emptyMap()

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
        sourceHeaders = if (pkg != null) computeSourceHeaders(pkg, langIndex) else emptyMap()

        bindInitial()
        configureSearchButtons()

        if (pkg != null) loadDetails(pkg, langIndex)
    }

    private fun computeSourceHeaders(pkg: String, langIndex: Int): Map<String, String> {
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
        val title = manga?.title ?: anime?.title ?: ""
        binding.extensionInfoTitle.text = title
        val cover = manga?.thumbnail_url ?: anime?.thumbnail_url
        if (!cover.isNullOrBlank() && sourceHeaders.isNotEmpty() && (cover.startsWith("http://") || cover.startsWith("https://"))) {
            binding.extensionInfoCover.loadImage(FileUrl(cover, sourceHeaders))
        } else {
            binding.extensionInfoCover.loadImage(cover)
        }
        blurImage(binding.extensionInfoBanner, cover, sourceHeaders)
        renderDetails()
    }

    private data class LoadedDetails(
        val details: Any?,
        val latest: Any?,
        val hasSub: Boolean = false,
        val hasDub: Boolean = false,
    )

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
                    if (isManga) {
                        val mgr: MangaExtensionManager = Injekt.get()
                        val ext = mgr.installedExtensionsFlow.value.find { it.pkgName == pkg }
                            ?: return@withContext LoadedDetails(null, null)
                        val source = ext.sources.getOrNull(langIndex) as? MangaSource
                            ?: return@withContext LoadedDetails(null, null)
                        val details = manga?.let { source.getMangaDetails(it) }
                        val latest = manga?.let { m ->
                            runCatching {
                                val list = source.getChapterList(m)
                                list.maxByOrNull { it.chapter_number }
                                    ?.takeIf { it.chapter_number >= 0f }
                                    ?: list.firstOrNull()
                            }.getOrNull()
                        }
                        LoadedDetails(details, latest)
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
            }
            latestChapter = result.latest as? SChapter
            latestEpisode = result.latest as? SEpisode
            latestHasSub = result.hasSub
            latestHasDub = result.hasDub
            renderDetails()
            renderLatest()
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
        if (showSynopsis) {
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
        val extensionPkg = intent.getStringExtra(EXTRA_PKG)
        val langIndex = intent.getIntExtra(EXTRA_LANG_INDEX, 0)

        binding.extensionInfoSearchAnilist.setOnClickListener {
            val titles = collectTitles()
            if (titles.isEmpty()) return@setOnClickListener
            val type = if (isManga) AniListQuickSearchDialogFragment.TYPE_MANGA
            else AniListQuickSearchDialogFragment.TYPE_ANIME
            AniListQuickSearchDialogFragment
                .newInstance(
                    titles = ArrayList(titles),
                    type = type,
                    extensionPkg = extensionPkg,
                    extensionLangIndex = langIndex,
                    sManga = manga,
                    sAnime = anime,
                )
                .show(supportFragmentManager, "ext_anilist_quick_search")
        }

        val muVisible = isManga && MangaUpdates.token != null
        binding.extensionInfoSearchMu.isVisible = muVisible
        binding.extensionInfoSearchMu.setOnClickListener {
            val titles = collectTitles()
            if (titles.isEmpty()) return@setOnClickListener
            MangaUpdatesQuickSearchDialogFragment
                .newInstance(
                    titles = ArrayList(titles),
                    extensionPkg = extensionPkg,
                    extensionLangIndex = langIndex,
                    sManga = manga,
                )
                .show(supportFragmentManager, "ext_mu_quick_search")
        }
    }

    private fun collectTitles(): List<String> {
        val list = mutableListOf<String>()
        (manga?.title ?: anime?.title)?.takeIf { it.isNotBlank() }?.let { list.add(it.trim()) }
        val description = manga?.description ?: anime?.description
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
