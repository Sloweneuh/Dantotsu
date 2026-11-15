package ani.dantotsu.media.anime

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.databinding.BottomSheetPlayerCommentsBinding
import ani.dantotsu.databinding.LayoutCommentInputBinding
import ani.dantotsu.media.comments.CommentsFragment
import ani.dantotsu.media.comments.CommentsHost

/**
 * The series' comments over the player's portrait panel, the video left playing and in view above
 * it: the same [CommentsFragment] as the details page's tab, with the sheet's own comment box.
 * New comments start labelled with the episode playing.
 */
class PlayerCommentsSheet : BottomSheetDialogFragment(), CommentsHost {

    private var _binding: BottomSheetPlayerCommentsBinding? = null
    private val binding get() = _binding!!

    override val commentBox: LayoutCommentInputBinding get() = binding.commentBox

    override val opensExpanded = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // In the activity's theme, not the sheet dialog's: that one makes ?android:colorBackground a
        // lighter surface, while the comments page inside (a child fragment) paints the activity's —
        // the two showed as a grey frame around the list.
        _binding = BottomSheetPlayerCommentsBinding.inflate(
            LayoutInflater.from(requireActivity()), container, false
        )
        // The box's own bar colour belongs on the details page, above its tab bar; here it would
        // be another band. The field keeps its outline.
        binding.commentBox.root.background = null
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        // The keyboard pushes the comment box up rather than covering it.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = maxOf(ime, nav))
            insets
        }
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(binding.playerCommentsContainer.id, CommentsFragment().apply {
                    arguments = Bundle().apply {
                        putInt("mediaId", args.getInt(ARG_MEDIA_ID))
                        putString("mediaName", args.getString(ARG_MEDIA_NAME))
                        putBoolean(CommentsFragment.ARG_SHOW_TITLE, true)
                        args.getInt(ARG_EPISODE, -1).takeIf { it >= 0 }
                            ?.let { putInt(CommentsFragment.ARG_POST_TAG, it) }
                    }
                })
                .commit()
        }
    }

    /**
     * No dim at all: the video above stays as it is, being still what's watched. Taken off the
     * window as the dialog is made — lowering the amount once it was showing left the video
     * washed out behind the sheet.
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0f)
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
        // The sheet's own surface would show as a band in another colour above the page: the
        // layout draws the sheet, rounded corners and all.
        (requireView().parent as? View)?.background = null
        // As tall as the room under the video, so the episode stays in sight while reading.
        // Here rather than when the view is made: only now is it in the sheet's container.
        val height = requireArguments().getInt(ARG_HEIGHT)
        if (height > 0) {
            binding.root.layoutParams = binding.root.layoutParams.apply { this.height = height }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "player_comments"
        private const val ARG_MEDIA_ID = "mediaId"
        private const val ARG_MEDIA_NAME = "mediaName"
        private const val ARG_EPISODE = "episode"
        private const val ARG_HEIGHT = "height"

        /** [episode] labels new comments; [height] is the sheet's, in pixels (0 for full). */
        fun newInstance(mediaId: Int, mediaName: String, episode: Int?, height: Int) =
            PlayerCommentsSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_MEDIA_ID, mediaId)
                    putString(ARG_MEDIA_NAME, mediaName)
                    putInt(ARG_EPISODE, episode ?: -1)
                    putInt(ARG_HEIGHT, height)
                }
            }
    }
}
