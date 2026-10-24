package ani.dantotsu.media.comments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.databinding.BottomSheetCommentFilterBinding
import com.google.android.material.chip.Chip

/**
 * Which comments the tab shows: [tag] is the chapter or episode. The order isn't here — the tab's
 * own sort button sets it.
 */
data class CommentFilters(
    val tag: Int? = null,
    val hideDownvoted: Boolean = false,
    val onlyWithReplies: Boolean = false,
    val onlyFriends: Boolean = false,
    val onlyMine: Boolean = false,
) {
    val narrowing: Boolean
        get() = tag != null || hideDownvoted || onlyWithReplies || onlyFriends || onlyMine
}

/**
 * The comments tab's filter sheet, in the filter sheets' shared style. Opened by [CommentsFragment]
 * as its child; reads the current filters from it and hands the new ones back on Apply.
 */
class CommentFilterBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetCommentFilterBinding? = null
    private val binding get() = _binding!!

    private val host: CommentsFragment? get() = parentFragment as? CommentsFragment

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetCommentFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = host ?: run { dismiss(); return }
        show(host.currentFilters())

        val (isAnime, progress) = host.mediaProgress()
        binding.commentFilterSectionTag.setTitle(
            getString(if (isAnime) R.string.comment_filter_episode else R.string.comment_filter_chapter)
        )
        if (progress != null) {
            binding.commentFilterTagChips.addView(tagChip(getString(R.string.comment_filter_your_progress, progress), progress))
            binding.commentFilterTagChips.addView(tagChip(getString(R.string.comment_filter_next, progress + 1), progress + 1))
        }
        binding.commentFilterTagChips.isVisible = progress != null
        binding.commentFilterFriends.isVisible = Anilist.userid != null
        binding.commentFilterMine.isVisible = CommentsAPI.userId != null

        binding.commentFilterReset.setOnClickListener { show(CommentFilters()) }
        binding.commentFilterCancel.setOnClickListener { dismiss() }
        binding.commentFilterApply.setOnClickListener {
            host.applyFilters(read())
            dismiss()
        }
    }

    private fun show(filters: CommentFilters) {
        binding.commentFilterTag.setText(filters.tag?.toString().orEmpty())
        binding.commentFilterHideDownvoted.isChecked = filters.hideDownvoted
        binding.commentFilterWithReplies.isChecked = filters.onlyWithReplies
        binding.commentFilterFriends.isChecked = filters.onlyFriends
        binding.commentFilterMine.isChecked = filters.onlyMine
    }

    private fun read(): CommentFilters {
        return CommentFilters(
            tag = binding.commentFilterTag.text?.toString()?.toIntOrNull(),
            hideDownvoted = binding.commentFilterHideDownvoted.isChecked,
            onlyWithReplies = binding.commentFilterWithReplies.isChecked,
            onlyFriends = binding.commentFilterFriends.isChecked,
            onlyMine = binding.commentFilterMine.isChecked,
        )
    }

    /** A chip in the filter sheets' style (item_chip). */
    private fun chip(text: String): Chip =
        (layoutInflater.inflate(R.layout.item_chip, binding.commentFilterTagChips, false) as Chip).apply {
            id = View.generateViewId()
            this.text = text
            // item_chip's negative vertical margins are for a single-row strip; a wrapping group spaces itself.
            (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
        }

    private fun tagChip(label: String, value: Int) = chip(label).apply {
        setOnClickListener { binding.commentFilterTag.setText(value.toString()) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
