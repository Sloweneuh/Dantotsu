package ani.dantotsu.media

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickChapter
import ani.dantotsu.connections.comick.ComickComic
import ani.dantotsu.connections.comick.ComickListComic
import ani.dantotsu.connections.comick.toComickReview
import ani.dantotsu.connections.mangaupdates.AniListQuickSearchDialogFragment
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.connections.mangaupdates.MangaUpdatesQuickSearchDialogFragment
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityComickMediaBinding
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleChipgroupMultilineBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.databinding.ItemChapterGapBinding
import ani.dantotsu.databinding.ItemChapterListBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.openOrCopyAnilistLink
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.chip.Chip
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.joery.animatedbottombar.AnimatedBottomBar
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.Locale

class ComickMediaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SLUG = "comick_slug"
        const val EXTRA_OPEN_CHAPTERS = "open_chapters"
        private const val HR_MARKER = ''
        private const val CHAPTER_GROUP_SIZE = 100
    }

    private lateinit var binding: ActivityComickMediaBinding
    private var allChapters: List<ComickChapter> = emptyList()
    private var chaptersLoaded = false
    private var currentTabIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityComickMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.comickMediaBottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.comickMediaPages.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }
        binding.comickMediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight + 16f.px
        }
        binding.comickMediaClose.setOnClickListener { finish() }

        val slug = intent.getStringExtra(EXTRA_SLUG)
            ?: run {
                val segments = intent.data?.pathSegments
                if (segments != null && segments.size >= 2 && segments[0] == "comic") segments[1]
                else null
            }
            ?: run { finish(); return }

        val openChapters = intent.getBooleanExtra(EXTRA_OPEN_CHAPTERS, false)

        setupBottomBar(openChapters)

        lifecycleScope.launch {
            val comickData = withContext(Dispatchers.IO) { ComickApi.getComicDetails(slug) }
            val comic = comickData?.comic
            if (comic == null) {
                binding.comickMediaProgress.visibility = View.GONE
                Toast.makeText(this@ComickMediaActivity, getString(R.string.failed_fetch_comick), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            setupHeader(comic)
            setupSourceButtons(comic)

            binding.comickMediaProgress.visibility = View.GONE
            displayInfo(comic)
            // Reveal the correct tab after data is ready — no animation on initial show
            if (currentTabIndex == 0) {
                binding.comickMediaInfoScroll.visibility = View.VISIBLE
            } else {
                binding.comickMediaChaptersScroll.visibility = View.VISIBLE
                comic.hid?.let { loadChapters(it) }
            }
        }
    }

    private fun setupBottomBar(startOnChapters: Boolean) {
        val navBar = binding.comickMediaBottomBar
        val infoTab = navBar.createTab(R.drawable.ic_round_info_24, R.string.info, R.id.info)
        val chaptersTab = navBar.createTab(R.drawable.ic_round_import_contacts_24, R.string.read, R.id.read)
        navBar.addTab(infoTab)
        navBar.addTab(chaptersTab)

        currentTabIndex = if (startOnChapters) 1 else 0
        navBar.selectTabAt(currentTabIndex)
        // Initial display with no animation
        binding.comickMediaInfoScroll.visibility = View.GONE
        binding.comickMediaChaptersScroll.visibility = View.GONE

        navBar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(lastIndex: Int, lastTab: AnimatedBottomBar.Tab?, newIndex: Int, newTab: AnimatedBottomBar.Tab) {
                if (newIndex == currentTabIndex) return
                slideToTab(from = currentTabIndex, to = newIndex)
                currentTabIndex = newIndex
                if (newIndex == 1) loadedHid?.let { ensureChaptersLoaded(it) }
            }
        })
    }

    private fun slideToTab(from: Int, to: Int) {
        val outView = if (from == 0) binding.comickMediaInfoScroll else binding.comickMediaChaptersScroll
        val inView  = if (to   == 0) binding.comickMediaInfoScroll else binding.comickMediaChaptersScroll
        if (outView.visibility != View.VISIBLE) {
            // Nothing visible yet (initial load still in progress) — just mark target
            return
        }
        val width = binding.comickMediaPages.width.toFloat().takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.toFloat()
        val goRight = to > from
        inView.translationX = if (goRight) width else -width
        inView.visibility = View.VISIBLE
        outView.animate().translationX(if (goRight) -width else width).setDuration(280)
            .withEndAction { outView.visibility = View.GONE; outView.translationX = 0f }.start()
        inView.animate().translationX(0f).setDuration(280).start()
        // If switching to the chapters tab and data is already ready, re-populate
        // using post{} so the view is measured at its final width first.
        if (to == 1 && chaptersLoaded) {
            inView.post { populateChapters() }
        }
    }

    private var loadedHid: String? = null

    private fun loadChapters(hid: String) {
        if (chaptersLoaded) return
        loadedHid = hid
        binding.comickChaptersProgress.isVisible = true
        lifecycleScope.launch {
            val chapters = withContext(Dispatchers.IO) { ComickApi.getChapters(hid) }
            binding.comickChaptersProgress.isVisible = false
            allChapters = chapters
            chaptersLoaded = true
            // post{} ensures chips are built after the view has been laid out at real width
            binding.comickMediaChaptersScroll.post { populateChapters() }
        }
    }

    // Called by setupBottomBar when tab 1 is selected after info is loaded
    private fun ensureChaptersLoaded(hid: String) {
        if (!chaptersLoaded) loadChapters(hid)
        else binding.comickMediaChaptersScroll.post { populateChapters() }
    }

    private fun chapterLimit(total: Int): Int {
        val d = total / 10.0
        return when { d < 25 -> 25; d < 50 -> 50; else -> 100 }
    }

    private fun populateChapters() {
        binding.comickChaptersList.removeAllViews()
        binding.comickChaptersChipGroup.removeAllViews()

        if (allChapters.isEmpty()) {
            binding.comickChaptersChipScroll.visibility = View.GONE
            binding.comickChaptersHeader.isVisible = false
            binding.comickChaptersEmpty.isVisible = true
            return
        }

        binding.comickChaptersEmpty.isVisible = false

        val total = allChapters.size
        val missing = computeMissingChapters(allChapters)
        binding.comickChaptersHeader.isVisible = true
        binding.comickChaptersHeader.text = buildChaptersHeader(missing)

        val limit = chapterLimit(total)
        if (total > limit) {
            buildChipGroups(total, limit)
        } else {
            binding.comickChaptersChipScroll.visibility = View.GONE
            showChapterRange(0, total - 1)
        }
    }

    private fun buildChipGroups(total: Int, limit: Int) {
        binding.comickChaptersChipScroll.visibility = View.VISIBLE
        val groupCount = (total + limit - 1) / limit
        var firstChip: Chip? = null

        for (groupIdx in 0 until groupCount) {
            val startIdx = groupIdx * limit
            val endIdx = minOf(startIdx + limit - 1, total - 1)

            val startChap = allChapters[startIdx].chap?.toDoubleOrNull()
            val endChap = allChapters[endIdx].chap?.toDoubleOrNull()
            val chipText = when {
                startChap != null && endChap != null ->
                    "Ch.${formatChapNum(startChap)} - Ch.${formatChapNum(endChap)}"
                else -> "${startIdx + 1}-${endIdx + 1}"
            }

            val chip = ItemChipBinding.inflate(LayoutInflater.from(this), binding.comickChaptersChipGroup, false).root
            chip.isCheckable = true
            chip.text = chipText
            chip.setTextColor(androidx.core.content.ContextCompat.getColorStateList(this, R.color.chip_text_color))
            chip.setOnClickListener {
                chip.isChecked = true
                showChapterRange(startIdx, endIdx)
            }
            binding.comickChaptersChipGroup.addView(chip)
            if (groupIdx == 0) {
                chip.isChecked = true
                firstChip = chip
            }
        }

        firstChip?.let { showChapterRange(0, minOf(limit - 1, total - 1)) }
    }

    private fun formatChapNum(n: Double) = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    private fun showChapterRange(startIdx: Int, endIdx: Int) {
        binding.comickChaptersList.removeAllViews()
        for (i in startIdx..endIdx) {
            val chapter = allChapters.getOrNull(i) ?: continue
            val b = ItemChapterListBinding.inflate(layoutInflater, binding.comickChaptersList, false)
            b.itemChapterDateLayout.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            b.itemDownload.isVisible = false
            b.itemChapterBrowser.isVisible = false
            b.itemEpisodeViewed.isVisible = false

            val chapNum = chapter.chap
            val chapTitle = chapter.title
            b.itemChapterNumber.text = when {
                !chapNum.isNullOrBlank() && !chapTitle.isNullOrBlank() -> "Ch.$chapNum: $chapTitle"
                !chapNum.isNullOrBlank() -> "Ch.$chapNum"
                !chapTitle.isNullOrBlank() -> chapTitle
                else -> getString(R.string.unknown)
            }

            val dateText = formatComickDate(chapter.created_at)
            val scan = chapter.group_name?.filterNot { it.isBlank() }?.joinToString(", ")
                ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            val hasDate = dateText.isNotBlank()
            val hasScan = !scan.isNullOrBlank()
            b.itemChapterDateLayout.isVisible = hasDate || hasScan
            b.itemChapterDate.isVisible = hasDate
            b.itemChapterDate.text = dateText
            b.itemChapterScan.isVisible = hasScan
            b.itemChapterScan.text = scan ?: ""
            b.itemChapterDateDivider.isVisible = hasDate && hasScan
            // No click — Comick is info-only, not a reader source
            b.root.isClickable = false
            b.root.isFocusable = false
            binding.comickChaptersList.addView(b.root)

            // Gap indicator between this chapter and the next
            if (i < endIdx) {
                val next = allChapters.getOrNull(i + 1) ?: continue
                val c = chapter.chap?.toDoubleOrNull() ?: continue
                val n = next.chap?.toDoubleOrNull() ?: continue
                val gap = n.toInt() - c.toInt() - 1
                if (gap > 0) {
                    val g = ItemChapterGapBinding.inflate(layoutInflater, binding.comickChaptersList, false)
                    g.itemChapterGapText.text = if (gap == 1) getString(R.string.chapter_missing_single)
                                                else getString(R.string.chapters_missing, gap)
                    binding.comickChaptersList.addView(g.root)
                }
            }
        }
    }

    private fun computeMissingChapters(chapters: List<ComickChapter>): Int {
        var missing = 0
        var prevInt: Int? = null
        for (ch in chapters) {
            val n = ch.chap?.toDoubleOrNull()?.toInt() ?: continue
            val prev = prevInt
            if (prev != null && n > prev + 1) missing += n - prev - 1
            prevInt = n
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

    private fun formatComickDate(isoDate: String?): String {
        if (isoDate.isNullOrBlank()) return ""
        return try {
            val epochMs = try {
                Instant.parse(isoDate).toEpochMilli()
            } catch (_: DateTimeParseException) {
                java.time.OffsetDateTime.parse(isoDate).toInstant().toEpochMilli()
            }
            val targetDate = Date(epochMs)
            val difference = Date().time - epochMs
            val days = difference / (1000 * 60 * 60 * 24)
            when {
                days == 0L -> {
                    val hours = difference / (1000 * 60 * 60)
                    if (hours > 0) "$hours hour${if (hours > 1) "s" else ""} ago" else "Just now"
                }
                days == 1L -> "1 day ago"
                days < 7 -> "$days days ago"
                else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(targetDate)
            }
        } catch (_: Exception) { "" }
    }

    private fun setupHeader(comic: ComickComic) {
        val coverUrl = comic.md_covers?.firstOrNull()?.b2key?.let { "https://meo.comick.pictures/$it" }
        if (coverUrl != null) {
            binding.comickMediaCover.loadImage(coverUrl)
            blurImage(binding.comickMediaBanner, coverUrl)
        }
        val englishTitle = comic.md_titles
            ?.filter { it.lang?.equals("en", ignoreCase = true) == true }
            ?.mapNotNull { it.title?.takeIf { t -> t.isNotBlank() && !hasCJK(t) } }
            ?.firstOrNull()
        binding.comickMediaTitle.text = englishTitle ?: comic.title ?: getString(R.string.unknown)
        binding.comickMediaScore.text = comic.bayesian_rating?.let { "★ $it" } ?: ""
    }

    private fun setupSourceButtons(comic: ComickComic) {
        val anilistId = comic.links?.al?.toIntOrNull()
        val muLink = comic.links?.mu?.trim()
        val titles = buildTitleList(comic)

        binding.comickMediaAnilistBtn.visibility = View.VISIBLE
        if (anilistId != null) {
            binding.comickMediaAnilistBtn.setText(R.string.comick_open_anilist)
            binding.comickMediaAnilistBtn.setOnClickListener {
                openOrCopyAnilistLink("https://anilist.co/manga/$anilistId")
            }
        } else {
            binding.comickMediaAnilistBtn.setText(R.string.comick_search_anilist)
            binding.comickMediaAnilistBtn.setOnClickListener {
                AniListQuickSearchDialogFragment
                    .newInstance(titles = ArrayList(titles), type = AniListQuickSearchDialogFragment.TYPE_MANGA)
                    .show(supportFragmentManager, "comick_anilist_quick_search")
            }
        }

        binding.comickMediaMuBtn.visibility = View.VISIBLE
        if (!muLink.isNullOrBlank()) {
            binding.comickMediaMuBtn.setText(R.string.comick_open_mangaupdates)
            binding.comickMediaMuBtn.setOnClickListener {
                val url = if (muLink.all { it.isDigit() }) {
                    "https://www.mangaupdates.com/series.html?id=$muLink"
                } else {
                    "https://www.mangaupdates.com/series/$muLink"
                }
                val uri = Uri.parse(url)
                if (uri.pathSegments?.firstOrNull() == "series") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                            setClass(this@ComickMediaActivity, MUMediaDetailsActivity::class.java)
                        })
                    } catch (_: Exception) {
                        openLinkInBrowser(url)
                    }
                } else {
                    openLinkInBrowser(url)
                }
            }
        } else {
            binding.comickMediaMuBtn.setText(R.string.mu_search_title)
            binding.comickMediaMuBtn.setOnClickListener {
                MangaUpdatesQuickSearchDialogFragment
                    .newInstance(titles = ArrayList(titles))
                    .show(supportFragmentManager, "comick_mu_quick_search")
            }
        }

        binding.comickMediaSourceButtons.visibility = View.VISIBLE

        // Store hid so the tab listener can trigger chapter fetch on demand
        val hid = comic.hid
        if (!hid.isNullOrBlank()) loadedHid = hid
    }

    private fun buildTitleList(comic: ComickComic): List<String> {
        val titles = mutableListOf<String>()
        comic.title?.takeIf { it.isNotBlank() }?.let { titles.add(it) }
        comic.md_titles
            ?.filter { it.lang?.equals("en", ignoreCase = true) == true && !it.title.isNullOrBlank() }
            ?.mapNotNull { it.title }
            ?.filterNot { hasCJK(it) || titles.contains(it) }
            ?.forEach { titles.add(it) }
        return titles
    }

    private fun hasCJK(text: String) = text.any { c ->
        c.code in 0x3040..0x309F || c.code in 0x30A0..0x30FF ||
        c.code in 0x4E00..0x9FFF || c.code in 0xAC00..0xD7AF || c.code in 0x1100..0x11FF
    }

    @SuppressLint("SetTextI18n")
    private fun displayInfo(comic: ComickComic) {
        val parent = binding.comickMediaContent

        parent.addView(buildStatsView(comic))

        val rawDescription = comic.parsed ?: comic.desc ?: getString(R.string.no_description_available)
        val htmlWithMarker = rawDescription.replace(Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE), HR_MARKER.toString())
        val parsedHtml = HtmlCompat.fromHtml(htmlWithMarker, HtmlCompat.FROM_HTML_MODE_LEGACY)
        val spannable = android.text.SpannableStringBuilder("\t\t\t").append(parsedHtml)
        var markerIdx = spannable.indexOf(HR_MARKER)
        while (markerIdx >= 0) {
            spannable.setSpan(object : android.text.style.ReplacementSpan() {
                override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
                    fm?.let {
                        val lh = (paint.descent() - paint.ascent()) * 1.4f
                        it.ascent = (-lh * 0.7f).toInt(); it.top = it.ascent
                        it.descent = (lh * 0.5f).toInt(); it.bottom = it.descent
                    }
                    return 0
                }
                override fun draw(canvas: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
                    val midY = (top + bottom) / 2f
                    val sc = paint.color; val sw = paint.strokeWidth; val ss = paint.style
                    paint.color = 0x50808080.toInt(); paint.strokeWidth = 1.5f; paint.style = android.graphics.Paint.Style.STROKE
                    canvas.drawLine(x, midY, canvas.width.toFloat(), midY, paint)
                    paint.color = sc; paint.strokeWidth = sw; paint.style = ss
                }
            }, markerIdx, markerIdx + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            markerIdx = spannable.indexOf(HR_MARKER, markerIdx + 1)
        }
        val descView = TextView(this).apply {
            this.text = spannable
            textSize = 14f
            maxLines = 5
            setPadding(16f.px, 16f.px, 16f.px, 16f.px)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 16f.px
                marginEnd = 16f.px
            }
        }
        descView.setOnClickListener {
            val anim = if (descView.maxLines == 5) android.animation.ObjectAnimator.ofInt(descView, "maxLines", 100).setDuration(950)
            else android.animation.ObjectAnimator.ofInt(descView, "maxLines", 5).setDuration(400)
            anim.start()
        }
        parent.addView(descView)

        val englishTitles = comic.md_titles
            ?.filter { it.lang?.equals("en", ignoreCase = true) == true && !it.title.isNullOrBlank() }
            ?.mapNotNull { it.title }
        if (!englishTitles.isNullOrEmpty()) {
            val bind = ItemTitleChipgroupBinding.inflate(LayoutInflater.from(this), parent, false)
            bind.itemTitle.text = getString(R.string.synonyms)
            englishTitles.forEach { title ->
                val chip = ItemChipSynonymBinding.inflate(LayoutInflater.from(this), bind.itemChipGroup, false).root
                chip.text = title
                chip.setOnLongClickListener { copyToClipboard(title); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.addView(bind.root)
        }

        // Latest chapter — shown after synonyms
        val finalChapterNum = comic.final_chapter?.toDoubleOrNull()
        val lastChapter = comic.last_chapter
        val hideLatest = comic.status == 2 && comic.translation_completed == true &&
                lastChapter != null && finalChapterNum != null && lastChapter >= finalChapterNum
        if (!hideLatest && lastChapter != null) {
            val chapText = "Ch." + if (lastChapter % 1.0 == 0.0) lastChapter.toInt().toString() else lastChapter.toString()

            val latestHeader = ItemTitleRecyclerBinding.inflate(LayoutInflater.from(this), parent, false)
            latestHeader.itemTitle.setText(R.string.latest_chapter)
            latestHeader.itemRecycler.visibility = View.GONE
            latestHeader.itemMore.visibility = View.VISIBLE
            latestHeader.itemMore.setSafeOnClickListener {
                binding.comickMediaBottomBar.selectTabAt(1)
            }

            val latestCard = ItemChapterListBinding.inflate(LayoutInflater.from(this), parent, false)
            latestCard.itemChapterDateLayout.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            latestCard.itemDownload.isVisible = false
            latestCard.itemChapterBrowser.isVisible = false
            latestCard.itemEpisodeViewed.isVisible = false
            latestCard.itemChapterNumber.text = chapText
            latestCard.itemChapterDateLayout.isVisible = false
            (latestCard.root.layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
                marginStart = 32f.px
                marginEnd = 16f.px
            }
            latestCard.root.isClickable = false
            latestCard.root.isFocusable = false

            parent.addView(latestHeader.root)
            parent.addView(latestCard.root)

            val hid = comic.hid
            if (!hid.isNullOrBlank()) {
                lifecycleScope.launch {
                    val latest = withContext(Dispatchers.IO) {
                        ComickApi.getLatestChapter(hid, nearChapter = lastChapter)
                    }
                    if (latest == null) return@launch
                    val chapNum = latest.chap
                    val chapTitle = latest.title
                    latestCard.itemChapterNumber.text = when {
                        !chapNum.isNullOrBlank() && !chapTitle.isNullOrBlank() -> "Ch.$chapNum: $chapTitle"
                        !chapNum.isNullOrBlank() -> "Ch.$chapNum"
                        else -> chapText
                    }
                    val dateText = formatComickDate(latest.created_at)
                    val scan = latest.group_name?.filter { it.isNotBlank() }?.joinToString(", ")
                        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                    val hasDate = dateText.isNotBlank()
                    val hasScan = !scan.isNullOrBlank()
                    latestCard.itemChapterDateLayout.isVisible = hasDate || hasScan
                    latestCard.itemChapterDate.isVisible = hasDate
                    latestCard.itemChapterDate.text = dateText
                    latestCard.itemChapterScan.isVisible = hasScan
                    latestCard.itemChapterScan.text = scan ?: ""
                    latestCard.itemChapterDateDivider.isVisible = hasDate && hasScan
                }
            }
        }

        val comickSlug = comic.slug
        if (!comickSlug.isNullOrBlank()) {
            val coversPlaceholder = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            parent.addView(coversPlaceholder)
            lifecycleScope.launch {
                val covers = withContext(Dispatchers.IO) { ComickApi.getCovers(comickSlug) }
                if (!covers.isNullOrEmpty()) {
                    ItemTitleRecyclerBinding.inflate(LayoutInflater.from(this@ComickMediaActivity), coversPlaceholder, false).apply {
                        itemTitle.setText(R.string.covers)
                        val coverAdapter = ComickCoverAdapter(covers)
                        itemRecycler.adapter = coverAdapter
                        itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                            this@ComickMediaActivity, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                        )
                        itemMore.visibility = View.VISIBLE
                        itemMore.setSafeOnClickListener { coverAdapter.showGallery(itemMore, getString(R.string.covers)) }
                        coversPlaceholder.addView(root)
                    }
                }
            }
        }

        if (comic.has_anime == true && comic.anime != null) {
            val animeInfo = comic.anime
            val infoText = buildString {
                if (!animeInfo.start.isNullOrBlank()) append(getString(R.string.anime_start_format, animeInfo.start))
                if (!animeInfo.end.isNullOrBlank()) append(getString(R.string.anime_end_format, animeInfo.end))
            }
            if (infoText.isNotBlank()) {
                ItemTitleTextBinding.inflate(LayoutInflater.from(this), parent, false).apply {
                    itemTitle.text = getString(R.string.anime_adaptation)
                    itemText.text = infoText
                    parent.addView(root)
                }
            }
        }

        val genres = comic.md_comic_md_genres
        if (!genres.isNullOrEmpty()) {
            val genresByGroup = genres.mapNotNull { it.md_genres }.groupBy { it.group?.lowercase() }
            listOf("genre" to "Genres:", "theme" to "Theme:", "content" to "Content:", "format" to "Format:").forEach { (groupType, label) ->
                val groupGenres = genresByGroup[groupType]
                if (groupGenres.isNullOrEmpty()) return@forEach
                val bind = ItemTitleChipgroupBinding.inflate(LayoutInflater.from(this), parent, false)
                bind.itemTitle.text = label
                groupGenres.forEach { genre ->
                    val name = genre.name ?: return@forEach
                    val slug = genre.slug
                    val chip = ItemChipBinding.inflate(LayoutInflater.from(this), bind.itemChipGroup, false).root
                    chip.text = name
                    chip.setOnClickListener {
                        startActivity(
                            Intent(this, SearchActivity::class.java)
                                .putExtra("type", "COMICK")
                                .putExtra("genre", slug)
                                .putExtra("genreName", name)
                                .putExtra("search", true)
                        )
                    }
                    chip.setOnLongClickListener { copyToClipboard(name); true }
                    bind.itemChipGroup.addView(chip)
                }
                parent.addView(bind.root)
            }
        }

        val categories = comic.mu_comics?.mu_comic_categories
        if (!categories.isNullOrEmpty()) {
            val bind = ItemTitleChipgroupMultilineBinding.inflate(LayoutInflater.from(this), parent, false)
            bind.itemTitle.text = "Tags"
            categories.sortedByDescending { it.positive_vote ?: 0 }.forEach { category ->
                val title = category.mu_categories?.title ?: return@forEach
                val slug = category.mu_categories?.slug
                val chip = ItemChipBinding.inflate(LayoutInflater.from(this), bind.itemChipGroup, false).root
                chip.text = title
                chip.setOnClickListener {
                    startActivity(
                        Intent(this, SearchActivity::class.java)
                            .putExtra("type", "COMICK")
                            .putExtra("category", slug)
                            .putExtra("categoryName", title)
                            .putExtra("search", true)
                    )
                }
                chip.setOnLongClickListener { copyToClipboard(title); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.addView(bind.root)
        }

        val recommendations = comic.recommendations
        if (!recommendations.isNullOrEmpty()) {
            val recsAsComics = recommendations.mapNotNull { rec ->
                val rel = rec.relates ?: return@mapNotNull null
                if (rel.slug.isNullOrBlank()) return@mapNotNull null
                ComickListComic(title = rel.title, slug = rel.slug, hid = rel.hid, last_chapter = null, md_titles = null, md_covers = rel.md_covers)
            }
            if (recsAsComics.isNotEmpty()) {
                ItemTitleRecyclerBinding.inflate(LayoutInflater.from(this), parent, false).apply {
                    itemTitle.setText(R.string.recommended)
                    val recAdapter = ComickListComicAdapter { rec ->
                        startActivity(Intent(this@ComickMediaActivity, ComickMediaActivity::class.java)
                            .putExtra(EXTRA_SLUG, rec.slug))
                    }
                    recAdapter.appendItems(recsAsComics)
                    itemRecycler.adapter = recAdapter
                    itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                        this@ComickMediaActivity, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                    )
                    itemMore.visibility = View.GONE
                    parent.addView(root)
                }
            }
        }

        val hid = comic.hid
        if (!hid.isNullOrBlank()) {
            val customListsPlaceholder = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            parent.addView(customListsPlaceholder)
            lifecycleScope.launch {
                val lists = withContext(Dispatchers.IO) { ComickApi.getComicLists(hid) }
                if (!lists.isNullOrEmpty()) {
                    ItemTitleRecyclerBinding.inflate(LayoutInflater.from(this@ComickMediaActivity), customListsPlaceholder, false).apply {
                        itemTitle.setText(R.string.comick_custom_lists)
                        val listAdapter = ComickCustomListAdapter(lists) { list ->
                            startActivity(
                                Intent(this@ComickMediaActivity, ComickListActivity::class.java)
                                    .putExtra(ComickListActivity.EXTRA_USER_ID, list.user_id)
                                    .putExtra(ComickListActivity.EXTRA_LIST_SLUG, list.slug)
                                    .putExtra(ComickListActivity.EXTRA_LIST_TITLE, list.title ?: list.slug)
                            )
                        }
                        itemRecycler.adapter = listAdapter
                        itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                            this@ComickMediaActivity, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                        )
                        itemMore.visibility = View.GONE
                        customListsPlaceholder.addView(root)
                    }
                }
            }
        }

        val reviews = comic.reviews?.mapNotNull { try { it.toComickReview() } catch (_: Exception) { null } }
        if (!reviews.isNullOrEmpty()) {
            ItemTitleRecyclerBinding.inflate(LayoutInflater.from(this), parent, false).apply {
                itemTitle.setText(R.string.reviews)
                val groupAdapter = GroupieAdapter()
                reviews.forEach { rev -> groupAdapter.add(ComickReviewAdapter(rev)) }
                itemRecycler.adapter = groupAdapter
                itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@ComickMediaActivity)
                itemMore.visibility = View.GONE
                parent.addView(root)
            }
        }
    }

    private fun buildStatsView(comic: ComickComic): LinearLayout {
        val b = FragmentMediaInfoBinding.inflate(layoutInflater)

        // Hide elements that duplicate the Activity header or don't apply here
        b.mediaInfoNameContainer.visibility = View.GONE
        b.mediaInfoNameRomajiContainer.visibility = View.GONE
        b.mediaInfoDescription.visibility = View.GONE

        b.mediaInfoMeanScore.text = comic.bayesian_rating ?: getString(R.string.unknown_value)

        b.mediaInfoStatus.text = when (comic.status) {
            1 -> getString(R.string.comick_status_ongoing)
            2 -> getString(R.string.comick_status_completed)
            3 -> getString(R.string.comick_status_cancelled)
            4 -> getString(R.string.comick_status_hiatus)
            else -> getString(R.string.unknown)
        }

        b.mediaInfoTranslationContainer.visibility = View.VISIBLE
        b.mediaInfoTranslation.text = if (comic.translation_completed == true)
            getString(R.string.comick_status_completed) else getString(R.string.comick_status_ongoing)

        val finalChapterNum = comic.final_chapter?.toDoubleOrNull()
        val lastChapter = comic.last_chapter
        val hideLatest = comic.status == 2 && comic.translation_completed == true &&
                lastChapter != null && finalChapterNum != null && lastChapter >= finalChapterNum
        if (hideLatest) {
            b.mediaInfoTotal.parent?.let { if (it is ViewGroup) it.visibility = View.GONE }
        } else {
            b.mediaInfoTotalTitle.setText(R.string.latest_chapter)
            b.mediaInfoTotal.text = lastChapter?.let {
                if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
            } ?: getString(R.string.unknown_value)
        }

        if (comic.final_chapter != null || comic.final_volume != null) {
            b.mediaInfoDurationContainer.visibility = View.VISIBLE
            (b.mediaInfoDurationContainer.getChildAt(0) as? TextView)
                ?.text = getString(R.string.final_chapter)
            b.mediaInfoDuration.text = when {
                comic.final_volume != null && comic.final_chapter != null ->
                    getString(R.string.volume_chapter_format, comic.final_volume, comic.final_chapter)
                comic.final_chapter != null -> getString(R.string.chapter_format, comic.final_chapter)
                else -> getString(R.string.volume_format, comic.final_volume)
            }
        }

        b.mediaInfoFormatLabel.text = getString(R.string.demographic)
        b.mediaInfoFormat.text = when (comic.demographic) {
            1 -> getString(R.string.shounen)
            2 -> getString(R.string.shoujo)
            3 -> getString(R.string.seinen)
            4 -> getString(R.string.josei)
            else -> getString(R.string.unknown)
        }

        b.mediaInfoSourceLabel.text = getString(R.string.format)
        b.mediaInfoSource.text = when (comic.country?.lowercase()) {
            "jp" -> getString(R.string.manga)
            "kr" -> getString(R.string.manhwa)
            "cn" -> getString(R.string.manhua)
            else -> comic.country?.uppercase() ?: getString(R.string.unknown)
        }

        b.mediaInfoStart.parent?.let { row ->
            if (row is ViewGroup) (row.getChildAt(0) as? TextView)?.text = getString(R.string.published)
        }
        b.mediaInfoStart.text = comic.year?.toString() ?: getString(R.string.unknown_value)

        b.mediaInfoEnd.parent?.let { if (it is ViewGroup) it.visibility = View.GONE }

        b.mediaInfoPopularity.parent?.let { row ->
            if (row is ViewGroup) (row.getChildAt(0) as? TextView)?.text = getString(R.string.followers)
        }
        b.mediaInfoPopularity.text = comic.user_follow_count?.toString() ?: getString(R.string.unknown_value)

        b.mediaInfoFavorites.parent?.let { row ->
            if (row is ViewGroup) (row.getChildAt(0) as? TextView)?.text = getString(R.string.ranking)
        }
        b.mediaInfoFavorites.text = "#${comic.follow_rank ?: getString(R.string.unknown_value)}"

        val container = b.mediaInfoContainer
        container.visibility = View.VISIBLE
        (container.parent as? ViewGroup)?.removeView(container)
        return container
    }
}
