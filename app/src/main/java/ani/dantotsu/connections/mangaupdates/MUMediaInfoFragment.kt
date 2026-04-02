package ani.dantotsu.connections.mangaupdates

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleChipgroupMultilineBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.SearchActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.px

/**
 * Info fragment for [MUMediaDetailsActivity].
 * Observes [MediaDetailsViewModel.mangaUpdatesSeries] (populated by the activity) and
 * renders MangaUpdates series metadata using the same layout as the existing info tabs.
 */
class MUMediaInfoFragment : Fragment() {

    private var _binding: FragmentMediaInfoBinding? = null
    private val binding get() = _binding!!
    private val model: MediaDetailsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mediaInfoContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += 128f.px + navBarHeight
        }

        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaInfoScroll.scrollTo(0, 0)
        }

        // Show spinner until series data arrives from the activity
        binding.mediaInfoProgressBar.visibility = View.VISIBLE

        model.mangaUpdatesSeries.observe(viewLifecycleOwner) { series ->
            if (series != null) displaySeriesDetails(series)
        }
    }

    private fun displaySeriesDetails(series: MUSeriesRecord) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.VISIBLE

        val tripleTab = "\t\t\t"
        binding.mediaInfoName.text = tripleTab + (series.title ?: getString(R.string.unknown_title))
        binding.mediaInfoName.setOnLongClickListener {
            copyToClipboard(series.title ?: "")
            true
        }
        binding.mediaInfoNameRomajiContainer.visibility = View.GONE

        // Parse chapter count and status text from the combined status field
        // e.g. "143 Chapters (Ongoing)" → chapters="143", status="Ongoing"
        var chaptersCount = "~"
        var statusText = getString(R.string.unknown)
        series.status?.let { full ->
            Regex("""(\d+)\s+Chapter""").find(full)?.groupValues?.get(1)?.let { chaptersCount = it }
            Regex("""\(([^)]+)\)""").find(full)?.groupValues?.get(1)?.let { statusText = it }
        }

        // Rating
        series.bayesian_rating?.let { ratingStr ->
            val rating = ratingStr.toDoubleOrNull()
            binding.mediaInfoMeanScore.text =
                if (rating != null) String.format("%.1f", rating) else ratingStr
        } ?: run { binding.mediaInfoMeanScore.text = getString(R.string.unknown_value) }

        binding.mediaInfoStatus.text = statusText
        binding.mediaInfoTotalTitle.setText(R.string.total_chaps)
        binding.mediaInfoTotal.text = chaptersCount
        binding.mediaInfoFormat.text = series.type ?: getString(R.string.manga)

        binding.mediaInfoSourceContainer.visibility = View.GONE

        // Primary author
        val firstAuthor =
            series.authors?.firstOrNull { it.type?.equals("Author", ignoreCase = true) == true }
        if (firstAuthor?.name != null) {
            binding.mediaInfoAuthorContainer.visibility = View.VISIBLE
            binding.mediaInfoAuthor.text = firstAuthor.name
        } else {
            binding.mediaInfoAuthorContainer.visibility = View.GONE
        }

        binding.mediaInfoStart.text = series.year ?: getString(R.string.unknown_value)

        // Hide end date and popularity rows
        binding.mediaInfoEnd.visibility = View.GONE
        (binding.mediaInfoEnd.parent as? ViewGroup)?.visibility = View.GONE
        binding.mediaInfoPopularity.visibility = View.GONE
        (binding.mediaInfoPopularity.parent as? ViewGroup)?.visibility = View.GONE

        // Re-label "Favourites" as "Followers" and show the count
        val favRow = binding.mediaInfoFavorites.parent as? ViewGroup
        favRow?.let { row ->
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                if (child is TextView && child.id != binding.mediaInfoFavorites.id) {
                    child.text = getString(R.string.followers)
                    break
                }
            }
        }
        series.rank?.lists?.let { lists ->
            val followers =
                (lists.reading ?: 0) + (lists.wish ?: 0) + (lists.complete ?: 0) +
                        (lists.unfinished ?: 0) + (lists.custom ?: 0)
            binding.mediaInfoFavorites.text =
                if (followers > 0) followers.toString() else getString(R.string.question_marks)
        } ?: run { binding.mediaInfoFavorites.text = getString(R.string.question_marks) }

        // Description
        val desc = (series.description ?: getString(R.string.no_description_available))
            .replace(Regex("""\n{3,}"""), "\n\n").trim()
        val markwon = buildMarkwon(
            requireContext(),
            userInputContent = false,
            fragment = this
        ) { link -> openLinkInBrowser(link) }
        markwon.setMarkdown(binding.mediaInfoDescription, desc)
        binding.mediaInfoDescription.movementMethod = LinkMovementMethod.getInstance()
        // Use a touch listener so that link span taps are consumed before View.onTouchEvent
        // can call performClick() — otherwise setOnClickListener intercepts all taps including links.
        binding.mediaInfoDescription.setOnTouchListener { v, event ->
            val tv = v as android.widget.TextView
            val sp = tv.text as? Spannable
            if (sp != null && (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP)) {
                val layout = tv.layout
                if (layout != null) {
                    val x = event.x - tv.totalPaddingLeft + tv.scrollX
                    val y = (event.y - tv.totalPaddingTop + tv.scrollY).toInt()
                    val line = layout.getLineForVertical(y)
                    val off = layout.getOffsetForHorizontal(line, x)
                    val links = sp.getSpans(off, off, ClickableSpan::class.java)
                    if (links.isNotEmpty()) {
                        if (event.action == MotionEvent.ACTION_UP) links[0].onClick(tv)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
        binding.mediaInfoDescription.setOnClickListener {
            ObjectAnimator.ofInt(
                binding.mediaInfoDescription,
                "maxLines",
                if (binding.mediaInfoDescription.maxLines == 5) 100 else 5
            ).setDuration(if (binding.mediaInfoDescription.maxLines == 5) 950L else 400L).start()
        }

        // ── Dynamic sections ──────────────────────────────────────────────────
        val parent = binding.mediaInfoContainer
        // Remove any previously added dynamic views to avoid duplicates on re-observe
        val toRemove = mutableListOf<View>()
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i).tag == "dynamic_mu_section") toRemove.add(parent.getChildAt(i))
        }
        toRemove.forEach { parent.removeView(it) }

        // Detailed status (expandable, markdown bold support)
        series.status?.let { rawStatus ->
            if (rawStatus.isNotBlank()) {
                val bind = ItemTitleTextBinding.inflate(LayoutInflater.from(context), parent, false)
                bind.itemTitle.setText(R.string.status_title)
                bind.itemText.text = parseSimpleBold(rawStatus.replace("\\", ""))
                bind.itemText.maxLines = 3
                bind.itemText.setOnClickListener {
                    ObjectAnimator.ofInt(
                        bind.itemText, "maxLines",
                        if (bind.itemText.maxLines == 3) 100 else 3
                    ).setDuration(400).start()
                }
                bind.root.tag = "dynamic_mu_section"
                parent.addView(bind.root)
            }
        }

        // Anime adaptation dates
        series.anime?.let { anime ->
            if (!anime.start.isNullOrBlank() || !anime.end.isNullOrBlank()) {
                val bind = ItemTitleTextBinding.inflate(LayoutInflater.from(context), parent, false)
                bind.itemTitle.text = getString(R.string.anime_adaptation)
                bind.itemText.text = buildString {
                    if (!anime.start.isNullOrBlank()) append("Start: ${anime.start}")
                    if (!anime.end.isNullOrBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("End: ${anime.end}")
                    }
                }
                bind.root.tag = "dynamic_mu_section"
                parent.addView(bind.root)
            }
        }

        // Alternative / associated titles
        if (!series.associated.isNullOrEmpty()) {
            val bind =
                ItemTitleChipgroupBinding.inflate(LayoutInflater.from(context), parent, false)
            bind.itemTitle.setText(R.string.synonyms)
            series.associated.forEach { assoc ->
                assoc.title?.let { title ->
                    val chip = ItemChipSynonymBinding
                        .inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                    chip.text = title
                    chip.setOnLongClickListener { copyToClipboard(title); true }
                    bind.itemChipGroup.addView(chip)
                }
            }
            bind.root.tag = "dynamic_mu_section"
            parent.addView(bind.root)
        }

        // Genres
        if (!series.genres.isNullOrEmpty()) {
            val bind =
                ItemTitleChipgroupBinding.inflate(LayoutInflater.from(context), parent, false)
            bind.itemTitle.setText(R.string.genres)
            val genreNames = series.genres.mapNotNull { it.genre }
            if (genreNames.isNotEmpty()) {
                bind.itemTitleAction.visibility = View.VISIBLE
                bind.itemTitleAction.text = getString(R.string.search_all)
                bind.itemTitleAction.setOnClickListener {
                    startActivity(
                        Intent(requireContext(), SearchActivity::class.java).apply {
                            putExtra("type", "MANGAUPDATES")
                            putExtra("search", true)
                            putStringArrayListExtra("genres", ArrayList(genreNames))
                        }
                    )
                }
            }
            series.genres.forEach { genreObj ->
                genreObj.genre?.let { genre ->
                    val chip = ItemChipBinding
                        .inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                    chip.text = genre
                    chip.isClickable = true
                    chip.setOnClickListener {
                        startActivity(
                            Intent(requireContext(), SearchActivity::class.java).apply {
                                putExtra("type", "MANGAUPDATES")
                                putExtra("search", true)
                                putExtra("genre", genre)
                            }
                        )
                    }
                    chip.setOnLongClickListener { copyToClipboard(genre); true }
                    bind.itemChipGroup.addView(chip)
                }
            }
            bind.root.tag = "dynamic_mu_section"
            parent.addView(bind.root)
        }

        // Categories
        if (!series.categories.isNullOrEmpty()) {
            val bind = ItemTitleChipgroupMultilineBinding
                .inflate(LayoutInflater.from(context), parent, false)
            bind.itemTitle.text = getString(R.string.categories)
            series.categories.forEach { cat ->
                cat.category?.let { category ->
                    val chip = ItemChipBinding
                        .inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                    chip.text = category
                    chip.isClickable = true
                    chip.setOnClickListener {
                        startActivity(
                            Intent(requireContext(), SearchActivity::class.java).apply {
                                putExtra("type", "MANGAUPDATES")
                                putExtra("search", true)
                                putExtra("category", category)
                            }
                        )
                    }
                    chip.setOnLongClickListener { copyToClipboard(category); true }
                    bind.itemChipGroup.addView(chip)
                }
            }
            bind.root.tag = "dynamic_mu_section"
            parent.addView(bind.root)
        }

        // Full author / artist list
        if (!series.authors.isNullOrEmpty()) {
            val bind = ItemTitleTextBinding.inflate(LayoutInflater.from(context), parent, false)
            bind.itemTitle.text = getString(R.string.authors)
            bind.itemText.text = series.authors.joinToString("\n") { author ->
                buildString {
                    append(author.name ?: "")
                    if (!author.type.isNullOrBlank()) append(" (${author.type})")
                }
            }
            bind.root.tag = "dynamic_mu_section"
            parent.addView(bind.root)
        }

        // Direct recommendations
        val directRecs = series.recommendations
        if (!directRecs.isNullOrEmpty()) {
            val items = directRecs.mapNotNull { rec ->
                val id = rec.seriesId ?: return@mapNotNull null
                MUMedia(
                    id = id,
                    title = rec.seriesName,
                    url = rec.seriesUrl,
                    coverUrl = rec.seriesImage?.url?.original ?: rec.seriesImage?.url?.thumb,
                    listId = -1,
                    userChapter = null,
                    userVolume = null,
                    latestChapter = null,
                    bayesianRating = null,
                    priority = null,
                )
            }
            if (items.isNotEmpty()) {
                val bind = ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false)
                bind.itemTitle.setText(R.string.recommended)
                bind.itemRecycler.adapter = MUMediaAdapter(items)
                bind.itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                    requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                )
                bind.itemMore.visibility = View.GONE
                bind.root.tag = "dynamic_mu_section"
                parent.addView(bind.root)
            }
        }

        // Category recommendations
        val categoryRecs = series.category_recommendations
        if (!categoryRecs.isNullOrEmpty()) {
            val items = categoryRecs.mapNotNull { rec ->
                val id = rec.seriesId ?: return@mapNotNull null
                MUMedia(
                    id = id,
                    title = rec.seriesName,
                    url = rec.seriesUrl,
                    coverUrl = rec.seriesImage?.url?.original ?: rec.seriesImage?.url?.thumb,
                    listId = -1,
                    userChapter = null,
                    userVolume = null,
                    latestChapter = null,
                    bayesianRating = null,
                    priority = null,
                )
            }
            if (items.isNotEmpty()) {
                val bind = ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false)
                bind.itemTitle.setText(R.string.category_recommendations)
                bind.itemRecycler.adapter = MUMediaAdapter(items)
                bind.itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                    requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                )
                bind.itemMore.visibility = View.GONE
                bind.root.tag = "dynamic_mu_section"
                parent.addView(bind.root)
            }
        }
    }

    /** Converts `**text**` markers to bold spans without a full Markwon pipeline. */
    private fun parseSimpleBold(text: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        var pos = 0
        val pattern = Regex("""\*\*([^*]+)\*\*""")
        pattern.findAll(text).forEach { match ->
            if (match.range.first > pos) out.append(text.substring(pos, match.range.first))
            val start = out.length
            out.append(match.groupValues[1])
            out.setSpan(
                StyleSpan(Typeface.BOLD), start, out.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            pos = match.range.last + 1
        }
        if (pos < text.length) out.append(text.substring(pos))
        return out
    }
}
