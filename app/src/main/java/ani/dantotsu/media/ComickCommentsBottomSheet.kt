package ani.dantotsu.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickComment
import ani.dantotsu.databinding.BottomSheetComickCommentsBinding
import ani.dantotsu.databinding.ItemComickCommentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The comments on a Comick entry or one of its chapters/episodes, as a sheet.
 *
 * Read-only: posting, voting and replying all need a Comick account, which the app doesn't carry,
 * so what's here is what the site shows to a signed-out reader. Replies arrive nested in their
 * parent and are laid out under it rather than fetched separately.
 */
class ComickCommentsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetComickCommentsBinding? = null
    private val binding get() = _binding!!

    private val adapter = CommentAdapter()
    private var sort = ComickApi.COMMENT_SORT_TOP

    private val hid get() = arguments?.getString(EXTRA_HID).orEmpty()
    private val isChapter get() = arguments?.getBoolean(EXTRA_IS_CHAPTER) ?: false
    private val heading get() = arguments?.getString(EXTRA_HEADING).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetComickCommentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.comickCommentsTitle.text = heading.ifBlank { getString(R.string.comick_comments) }
        binding.comickCommentsList.layoutManager = LinearLayoutManager(requireContext())
        binding.comickCommentsList.adapter = adapter

        binding.comickCommentsSort.setOnClickListener {
            sort = if (sort == ComickApi.COMMENT_SORT_TOP) ComickApi.COMMENT_SORT_NEWEST
            else ComickApi.COMMENT_SORT_TOP
            binding.comickCommentsSort.setText(
                if (sort == ComickApi.COMMENT_SORT_TOP) R.string.comick_comments_sort_top
                else R.string.comick_comments_sort_newest
            )
            load()
        }

        // The sheet is a list: without a height it wraps its content and a long thread opens as a
        // full-screen wall, while a three-comment chapter would still reserve the whole screen.
        binding.comickCommentsList.updateLayoutParams<ViewGroup.LayoutParams> {
            height = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        }

        load()
    }

    private fun load() {
        binding.comickCommentsProgress.isVisible = true
        binding.comickCommentsEmpty.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                if (isChapter) ComickApi.getChapterComments(hid, sort)
                else ComickApi.getComicComments(hid, sort)
            }
            if (_binding == null) return@launch
            val comments = page?.comments.orEmpty()
            binding.comickCommentsProgress.isVisible = false
            adapter.submit(comments)
            binding.comickCommentsEmpty.isVisible = comments.isEmpty()
            binding.comickCommentsList.isVisible = comments.isNotEmpty()
            binding.comickCommentsSubtitle.text = resources.getQuantityString(
                R.plurals.comick_comment_count, page?.total ?: comments.size, page?.total ?: comments.size
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** A comment and how deep it sits, so replies can be indented under what they answer. */
    private data class Row(val comment: ComickComment, val depth: Int)

    private inner class CommentAdapter : RecyclerView.Adapter<CommentHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(comments: List<ComickComment>) {
            rows = comments.flatMap { comment ->
                listOf(Row(comment, 0)) +
                    comment.other_comments.orEmpty().map { Row(it, 1) }
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CommentHolder(
            ItemComickCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: CommentHolder, position: Int) =
            holder.bind(rows[position])
    }

    private inner class CommentHolder(private val bind: ItemComickCommentBinding) :
        RecyclerView.ViewHolder(bind.root) {
        fun bind(row: Row) = ComickCommentViews.bind(bind, row.comment, row.depth)
    }

    companion object {
        private const val EXTRA_HID = "hid"
        private const val EXTRA_IS_CHAPTER = "isChapter"
        private const val EXTRA_HEADING = "heading"

        /** Comments on the entry itself. [heading] is the entry's title. */
        fun forComic(hid: String, heading: String) = newInstance(hid, false, heading)

        /** Comments on one chapter or episode. [heading] names it, e.g. "Ch.232". */
        fun forChapter(hid: String, heading: String) = newInstance(hid, true, heading)

        private fun newInstance(hid: String, isChapter: Boolean, heading: String) =
            ComickCommentsBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_HID, hid)
                    putBoolean(EXTRA_IS_CHAPTER, isChapter)
                    putString(EXTRA_HEADING, heading)
                }
            }
    }
}
