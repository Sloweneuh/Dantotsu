package ani.dantotsu.media.comments

import ani.dantotsu.databinding.LayoutCommentInputBinding
import nl.joery.animatedbottombar.AnimatedBottomBar

/**
 * Where a [CommentsFragment] lives: the details page (its comments tab) or a fragment holding it,
 * such as the player's comments sheet. The fragment finds it as its parent fragment, else its
 * activity.
 */
interface CommentsHost {
    /** The comment box, kept by the host: it outlives the fragment's view. */
    val commentBox: LayoutCommentInputBinding

    /** A tab bar the list should stay clear of, if the host has one. */
    val commentsNavBar: AnimatedBottomBar? get() = null

    /** False while the host is still setting itself up; the fragment then shows nothing. */
    val commentsReady: Boolean get() = true
}
