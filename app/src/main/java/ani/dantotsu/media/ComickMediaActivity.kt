package ani.dantotsu.media

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickComic
import ani.dantotsu.connections.comick.ComickListComic
import ani.dantotsu.connections.comick.toComickReview
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityComickMediaBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleChipgroupMultilineBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.openOrCopyAnilistLink
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComickMediaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SLUG = "comick_slug"
        private const val HR_MARKER = ''
    }

    private lateinit var binding: ActivityComickMediaBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityComickMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.comickMediaRoot.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
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
            binding.comickMediaContent.visibility = View.VISIBLE
            displayInfo(comic)
        }
    }

    private fun setupHeader(comic: ComickComic) {
        val coverUrl = comic.md_covers?.firstOrNull()?.b2key?.let { "https://meo.comick.pictures/$it" }
        if (coverUrl != null) {
            binding.comickMediaCover.loadImage(coverUrl)
            blurImage(binding.comickMediaBanner, coverUrl)
        }
        binding.comickMediaTitle.text = comic.title ?: getString(R.string.unknown)
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
                showQuickSearchDialog(R.string.comick_search_anilist, titles) { title ->
                    startActivity(
                        Intent(this, SearchActivity::class.java)
                            .putExtra("type", "MANGA")
                            .putExtra("query", title)
                            .putExtra("search", true)
                    )
                }
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
                showQuickSearchDialog(R.string.mu_search_title, titles) { title ->
                    startActivity(
                        Intent(this, SearchActivity::class.java)
                            .putExtra("type", "MANGAUPDATES")
                            .putExtra("query", title)
                            .putExtra("search", true)
                    )
                }
            }
        }

        binding.comickMediaSourceButtons.visibility = View.VISIBLE
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

    private fun showQuickSearchDialog(@StringRes titleRes: Int, titles: List<String>, onSelect: (String) -> Unit) {
        if (titles.isEmpty()) return
        CustomBottomDialog.newInstance().apply {
            setTitleText(getString(titleRes))
            titles.forEach { title ->
                addView(android.widget.TextView(this@ComickMediaActivity).apply {
                    text = title
                    textSize = 16f
                    val p = 16f.px
                    setPadding(p, p, p, p)
                    setTextColor(ContextCompat.getColor(this@ComickMediaActivity, R.color.bg_opp))
                    val outValue = android.util.TypedValue()
                    this@ComickMediaActivity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        onSelect(title)
                        dismiss()
                    }
                })
            }
        }.show(supportFragmentManager, "comick_media_quicksearch")
    }

    @SuppressLint("SetTextI18n")
    private fun displayInfo(comic: ComickComic) {
        val parent = binding.comickMediaContent

        // Stats table
        parent.addView(buildStatsTable(comic))

        // Description with <hr> rendering
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
            setPadding(16f.px, 4f.px, 16f.px, 16f.px)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        descView.setOnClickListener {
            val anim = if (descView.maxLines == 5) android.animation.ObjectAnimator.ofInt(descView, "maxLines", 100).setDuration(950)
            else android.animation.ObjectAnimator.ofInt(descView, "maxLines", 5).setDuration(400)
            anim.start()
        }
        parent.addView(descView)

        // English synonyms
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

        // Covers (async-loaded)
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

        // Anime adaptation info
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

        // Genres grouped by type
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

        // Tags (MangaUpdates categories)
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

        // Custom lists containing this comic
        val hid = comic.hid
        if (!hid.isNullOrBlank()) {
            lifecycleScope.launch {
                val lists = withContext(Dispatchers.IO) { ComickApi.getComicLists(hid) }
                if (!lists.isNullOrEmpty()) {
                    ItemTitleRecyclerBinding.inflate(LayoutInflater.from(this@ComickMediaActivity), parent, false).apply {
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
                        parent.addView(root)
                    }
                }
            }
        }

        // Recommendations as Comick compact grid
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

        // Reviews (embedded in payload)
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

    private fun buildStatsTable(comic: ComickComic): TableLayout {
        val table = TableLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(32f.px, 16f.px, 32f.px, 16f.px)
        }
        val boldFont = ResourcesCompat.getFont(this, R.font.poppins_bold)
        val primaryColor = run {
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.colorPrimary, tv, true)
            tv.data
        }

        fun addRow(label: String, value: String, tint: Boolean = false, visible: Boolean = true) {
            val row = TableRow(this).apply {
                layoutParams = TableLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24f.px)
                visibility = if (visible) View.VISIBLE else View.GONE
            }
            row.addView(TextView(this).apply {
                text = label; alpha = 0.58f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = value; typeface = boldFont
                textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                if (tint) setTextColor(primaryColor)
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            })
            table.addView(row)
        }

        addRow(getString(R.string.mean_score), comic.bayesian_rating ?: getString(R.string.unknown_value), tint = true)
        addRow(getString(R.string.status_title), when (comic.status) {
            1 -> getString(R.string.comick_status_ongoing)
            2 -> getString(R.string.comick_status_completed)
            3 -> getString(R.string.comick_status_cancelled)
            4 -> getString(R.string.comick_status_hiatus)
            else -> getString(R.string.unknown)
        })
        addRow(getString(R.string.translation), if (comic.translation_completed == true) getString(R.string.comick_status_completed) else getString(R.string.comick_status_ongoing))

        val finalChapterNum = comic.final_chapter?.toDoubleOrNull()
        val lastChapter = comic.last_chapter
        val hideLatest = comic.status == 2 && comic.translation_completed == true &&
                lastChapter != null && finalChapterNum != null && lastChapter >= finalChapterNum
        if (!hideLatest) {
            addRow(getString(R.string.latest_chapter), lastChapter?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: getString(R.string.unknown_value))
        }

        if (comic.final_chapter != null || comic.final_volume != null) {
            val finalText = when {
                comic.final_volume != null && comic.final_chapter != null -> getString(R.string.volume_chapter_format, comic.final_volume, comic.final_chapter)
                comic.final_chapter != null -> getString(R.string.chapter_format, comic.final_chapter)
                else -> getString(R.string.volume_format, comic.final_volume)
            }
            addRow(getString(R.string.final_chapter), finalText)
        }

        addRow(getString(R.string.demographic), when (comic.demographic) {
            1 -> getString(R.string.shounen)
            2 -> getString(R.string.shoujo)
            3 -> getString(R.string.seinen)
            4 -> getString(R.string.josei)
            else -> getString(R.string.unknown)
        })
        addRow(getString(R.string.format), when (comic.country?.lowercase()) {
            "jp" -> getString(R.string.manga)
            "kr" -> getString(R.string.manhwa)
            "cn" -> getString(R.string.manhua)
            else -> comic.country?.uppercase() ?: getString(R.string.unknown)
        })
        addRow(getString(R.string.published), comic.year?.toString() ?: getString(R.string.unknown_value))
        addRow(getString(R.string.followers), comic.user_follow_count?.toString() ?: getString(R.string.unknown_value))
        addRow(getString(R.string.ranking), "#${comic.follow_rank ?: getString(R.string.unknown_value)}")

        return table
    }
}
