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
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetNovelSearchBinding
import ani.dantotsu.media.novel.ebook.SearchHit
import ani.dantotsu.media.novel.ebook.SearchResults
import com.google.android.material.color.MaterialColors

/**
 * Search in the open book: the matches come in chapter by chapter while the search runs, and
 * foliate-js outlines them on the pages. Picking one goes to it and leaves the outlines up;
 * closing the sheet without picking takes them down.
 *
 * A streamed novel's book is a single chapter, so there the search covers that chapter
 * ([NovelReaderActivity.searchesWholeBook] says which, for the hint).
 */
class NovelSearchSheet : BottomSheetDialogFragment() {

    override val opensExpanded = true

    private var _binding: BottomSheetNovelSearchBinding? = null
    private val binding get() = _binding!!

    private val rows = mutableListOf<Row>()
    private var hits = 0
    private var picked = false

    private sealed class Row {
        class Chapter(val label: String) : Row()
        class Hit(val hit: SearchHit) : Row()
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
            if (wholeBook) R.string.novel_search_hint_book else R.string.novel_search_hint_chapter
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

    private fun run(query: String) {
        val q = query.trim()
        rows.clear()
        hits = 0
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
        reader?.search(q, ::onResults, ::onDone)
    }

    private fun onResults(results: SearchResults) {
        if (_binding == null || results.items.isEmpty()) return
        val start = rows.size
        if (results.label.isNotBlank()) rows += Row.Chapter(results.label)
        results.items.forEach { rows += Row.Hit(it) }
        hits += results.items.size
        adapter.notifyItemRangeInserted(start, rows.size - start)
        binding.novelSearchStatus.text = resources.getQuantityString(R.plurals.novel_search_matches, hits, hits)
    }

    private fun onDone() {
        if (_binding == null) return
        binding.novelSearchProgress.visibility = View.INVISIBLE
        if (hits == 0) binding.novelSearchStatus.text = getString(R.string.novel_search_none)
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
                is Row.Hit -> {
                    val hit = row.hit
                    text.text = SpannableStringBuilder().apply {
                        append(hit.pre.trimStart())
                        val start = length
                        append(hit.match)
                        setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        append(hit.post.trimEnd())
                    }
                    text.setOnClickListener {
                        picked = true
                        reader?.goTo(hit.cfi)
                        dismiss()
                    }
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
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
