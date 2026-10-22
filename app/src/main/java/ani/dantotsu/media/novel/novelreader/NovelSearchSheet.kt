package ani.dantotsu.media.novel.novelreader

import android.content.DialogInterface
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetNovelSearchBinding
import ani.dantotsu.media.novel.ebook.SearchResults
import ani.dantotsu.parsers.novel.lnreader.LNReaderSession
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search in the open book: the matches come in chapter by chapter while the search runs, and
 * foliate-js outlines them on the pages. Picking one goes to it and leaves the outlines up;
 * closing the sheet without picking takes them down.
 *
 * A streamed novel's book is a single chapter, so for one the open chapter is searched in the page
 * and then every other chapter already on the device is searched from its cached book (see
 * [NovelChapterSearch]); the status line says how many chapters that covered.
 */
class NovelSearchSheet : BottomSheetDialogFragment() {

    override val opensExpanded = true

    private var _binding: BottomSheetNovelSearchBinding? = null
    private val binding get() = _binding!!

    private val rows = mutableListOf<Row>()
    private var hits = 0
    private var picked = false
    private var query = ""
    private var otherChapters: Job? = null

    /** Chapters searched out of the novel's total, for a streamed novel; null for a whole book. */
    private var coverage: Pair<Int, Int>? = null

    private sealed class Row {
        class Chapter(val label: String) : Row()
        /** A match in the open book, found by the page. */
        class Open(val cfi: String, val pre: String, val match: String, val post: String) : Row()
        /** A match in another chapter of a streamed novel, found in its cached book. */
        class Other(val hit: NovelChapterSearch.Hit) : Row()
    }

    private val reader get() = activity as? NovelReaderActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetNovelSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val wholeBook = reader?.searchesWholeBook() ?: true
        binding.novelSearchField.hint = getString(
            if (wholeBook) R.string.novel_search_hint_book else R.string.novel_search_hint_novel
        )
        binding.novelSearchStatus.text = ""
        binding.novelSearchResults.adapter = adapter

        binding.novelSearchInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                run(binding.novelSearchInput.text?.toString().orEmpty())
                true
            } else false
        }
        binding.novelSearchInput.requestFocus()
    }

    private fun run(input: String) {
        val q = input.trim()
        otherChapters?.cancel()
        rows.clear()
        hits = 0
        coverage = null
        query = q
        @Suppress("NotifyDataSetChanged") // A new search replaces the list wholesale.
        adapter.notifyDataSetChanged()
        if (q.isEmpty()) {
            reader?.clearSearch()
            binding.novelSearchStatus.text = ""
            binding.novelSearchProgress.visibility = View.INVISIBLE
            return
        }
        binding.novelSearchProgress.visibility = View.VISIBLE
        binding.novelSearchStatus.text = getString(R.string.novel_searching)
        reader?.search(q, ::onResults, ::onOpenDone)
    }

    private fun onResults(results: SearchResults) {
        if (_binding == null || results.items.isEmpty()) return
        add(results.label, results.items.map { Row.Open(it.cfi, it.pre, it.match, it.post) })
    }

    private fun add(label: String, found: List<Row>) {
        val start = rows.size
        if (label.isNotBlank()) rows += Row.Chapter(label)
        rows += found
        hits += found.size
        adapter.notifyItemRangeInserted(start, rows.size - start)
        status()
    }

    private fun status() {
        val matches = if (hits == 0) getString(R.string.novel_search_none)
        else resources.getQuantityString(R.plurals.novel_search_matches, hits, hits)
        binding.novelSearchStatus.text = coverage?.let { (searched, total) ->
            getString(R.string.novel_search_coverage, matches, searched, total)
        } ?: matches
    }

    /** The open book is done; for a streamed novel, the other chapters on the device come next. */
    private fun onOpenDone() {
        if (_binding == null) return
        val novel = LNReaderSession.novel?.takeIf { LNReaderSession.isActive }
        if (novel == null) {
            binding.novelSearchProgress.visibility = View.INVISIBLE
            status()
            return
        }
        val q = query
        val context = requireContext().applicationContext
        otherChapters = viewLifecycleOwner.lifecycleScope.launch {
            val chapters = withContext(Dispatchers.IO) {
                NovelChapterSearch.searchable(context, novel, LNReaderSession.currentIndex)
            }
            coverage = (chapters.size + 1) to novel.chapters.size
            status()
            for (index in chapters) {
                val found = withContext(Dispatchers.IO) { NovelChapterSearch.search(context, novel, index, q) }
                if (_binding == null || q != query) return@launch
                if (found != null && found.hits.isNotEmpty()) add(found.label, found.hits.map { Row.Other(it) })
            }
            binding.novelSearchProgress.visibility = View.INVISIBLE
            status()
        }
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Chapter) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = if (viewType == 0) TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val dp = resources.displayMetrics.density
                setPadding((4 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            } else LayoutInflater.from(parent.context).inflate(R.layout.item_novel_search_hit, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val text = holder.itemView as TextView
            when (val row = rows[position]) {
                is Row.Chapter -> text.text = row.label
                is Row.Open -> {
                    text.text = excerpt(row.pre, row.match, row.post)
                    text.setOnClickListener {
                        picked = true
                        reader?.goTo(row.cfi)
                        dismiss()
                    }
                }
                is Row.Other -> {
                    text.text = excerpt(row.hit.pre, row.hit.match, row.hit.post)
                    text.setOnClickListener {
                        picked = true
                        reader?.openSearchHit(row.hit.chapterIndex, query, row.hit.occurrence)
                        dismiss()
                    }
                }
            }
        }
    }

    private fun excerpt(pre: String, match: String, post: String) = SpannableStringBuilder().apply {
        append(pre.trimStart())
        val start = length
        append(match)
        setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(post.trimEnd())
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        otherChapters?.cancel()
        // Picked: the outlines stay to show where the match is. Otherwise the search is over.
        if (!picked) reader?.clearSearch()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "NovelSearchSheet"
    }
}
