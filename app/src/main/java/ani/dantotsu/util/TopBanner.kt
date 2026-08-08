package ani.dantotsu.util

import android.annotation.SuppressLint
import android.app.Activity
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import ani.dantotsu.databinding.ViewTopBannerBinding
import ani.dantotsu.statusBarHeight
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A persistent notice that slides down over whatever screen the user is on.
 *
 * The app kept telling people things with snackbars that they had no realistic chance of reading: a
 * few seconds, at the bottom, on one screen, in the same shape for every unrelated message — and
 * with an action attached, so missing it meant missing the only way to act. Anything the user is
 * genuinely expected to *do* something about belongs here instead: distinct, wherever they are, and
 * gone only when they say so.
 *
 * Not a replacement for snackbars in general. A snackbar is right for "saved", "copied", "no
 * internet" — outcomes that need no response. This is for the ones that do.
 */
object TopBanner {

    private const val TAG = "app_top_banner"
    private const val ANIM_MS = 280L

    /**
     * @param id distinguishes what a banner is *about*, so a second showing of the same notice
     *   replaces it rather than stacking, and a different one can tell it isn't its own.
     * @param onAction invoked on the UI thread after the banner animates away; null shows no button.
     * @param onDismiss invoked when the user swipes it away without acting.
     */
    data class Spec(
        val id: String,
        val iconRes: Int,
        val title: String,
        val subtitle: String? = null,
        val actionLabel: String? = null,
        val onAction: (Activity) -> Unit = {},
        val onDismiss: () -> Unit = {},
    )

    /** The banner currently on screen, so a lower-priority notice doesn't displace a live one. */
    @Volatile
    private var showingId: String? = null

    /**
     * Weakly held so a banner left on a finished activity can't keep it alive; [dismiss] treats a
     * collected reference the same as nothing being shown.
     */
    private var shownCard: WeakReference<View>? = null
    private var shownParent: WeakReference<ViewGroup>? = null

    /** Whether this notice has been raised and not yet taken down, wherever its card ended up. */
    fun isShowing(id: String): Boolean = showingId == id

    /**
     * Whether this notice's card is on [activity]'s screen — the question a caller deciding whether
     * to show it has to ask.
     *
     * Not the same question as [isShowing], and the difference is the whole point: the card is added
     * to one activity's content root, while this object outlives every activity. A rotation destroys
     * the view and builds a new screen without it; moving to another screen leaves the card behind
     * on the one before. The id stayed set through both, so the notice read as already-visible and
     * was skipped — which is how a banner disappeared for good on a rotation, and never followed the
     * user anywhere despite being built to.
     */
    fun isShowingIn(activity: Activity, id: String): Boolean {
        if (showingId != id) return false
        val card = shownCard?.get() ?: return false
        return card.isAttachedToWindow &&
            card.parent === activity.findViewById<ViewGroup>(android.R.id.content)
    }

    /**
     * Takes down a banner whose subject has stopped being true — sync switched off underneath a
     * conflict notice, say. Without this the card stays on screen offering an action that can no
     * longer do anything, which reads as the feature being broken rather than off.
     */
    fun dismiss(id: String) {
        if (showingId != id) return
        // A card belonging to an activity that has since gone away has nothing to animate; drop the
        // bookkeeping instead, so the next screen isn't told a banner is up that no longer exists.
        val card = shownCard?.get()?.takeIf { it.isAttachedToWindow }
        val parent = shownParent?.get()
        if (card == null || parent == null) {
            showingId = null
            shownCard = null
            shownParent = null
            return
        }
        hide(parent, card) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(activity: Activity, spec: Spec) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // Whether this notice is already up and is only moving to another screen.
        //
        // Following the user means building a new card on every activity change and every rotation,
        // because the card belongs to the content root it was added to. Sliding it in each time made
        // one persistent notice look like a notice that fired again on every navigation — the same
        // repetitive quality this was built to get away from. A carried-over banner is simply
        // already there, which is what a banner that never left should look like.
        val carriedOver = showingId == spec.id
        // An activity change leaves the old view behind with its own content root; clear any
        // stale copy from this one before adding.
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }

        val binding = ViewTopBannerBinding.inflate(LayoutInflater.from(activity))
        val card = binding.root.apply { tag = TAG }
        binding.topBannerIcon.setImageResource(spec.iconRes)
        binding.topBannerTitle.text = spec.title
        binding.topBannerInfo.isVisible = !spec.subtitle.isNullOrBlank()
        binding.topBannerInfo.text = spec.subtitle
        binding.topBannerAction.isVisible = !spec.actionLabel.isNullOrBlank()
        binding.topBannerAction.text = spec.actionLabel

        val side = (12 * activity.resources.displayMetrics.density).toInt()
        // Sitting right under the status bar put the card directly over the back/close button
        // most screens place there (commonly a ~48dp toolbar), and on the home screen, over the
        // settings avatar. A standard app-bar height of clearance isn't a guarantee for every
        // screen's layout, but it clears the common case instead of the previous 12dp, which
        // cleared none of them.
        val topClearance = (56 * activity.resources.displayMetrics.density).toInt()
        card.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply { setMargins(side, statusBarHeight + topClearance, side, 0) }
        content.addView(card)
        showingId = spec.id
        shownCard = WeakReference(card)
        shownParent = WeakReference(content)

        if (carriedOver) {
            card.alpha = 1f
        } else {
            card.alpha = 0f
            // Posted because the slide starts from the card's own height, which isn't known until
            // it has been measured.
            card.post {
                card.translationY = -(card.height + side).toFloat()
                card.animate().translationY(0f).alpha(1f).setDuration(ANIM_MS).start()
            }
        }

        binding.topBannerAction.setOnClickListener {
            hide(content, card) { spec.onAction(activity) }
        }

        // Flick up, or swipe right, to dismiss — the only way, now that the close button is gone.
        // Deliberately no timeout: being missable is the failure this exists to correct.
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        card.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0f
            private var startY = 0f
            private var dragging = false

            // Which way this gesture committed to, once one axis clears the touch slop first —
            // locked for the rest of the gesture so a slightly diagonal swipe doesn't fight itself
            // by fighting between a vertical translation and a horizontal one.
            private var vertical = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = v.translationX
                        startY = v.translationY
                        dragging = false
                        // Must return true: an OnTouchListener that returns false on ACTION_DOWN
                        // never gets the MOVE/UP that follow, which silently killed the swipe
                        // entirely. Safe to claim here regardless — card's own listener (as
                        // opposed to onInterceptTouchEvent) only ever sees touches the action/close
                        // buttons didn't already consume, so their taps are unaffected.
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                            dragging = true
                            vertical = abs(dy) >= abs(dx)
                        }
                        if (!dragging) return false
                        if (vertical) {
                            v.translationY = startY + min(0f, dy)
                            v.alpha = (1f + v.translationY / v.height).coerceIn(0f, 1f)
                        } else {
                            v.translationX = startX + max(0f, dx)
                            v.alpha = (1f - v.translationX / v.width).coerceIn(0f, 1f)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!dragging) return false
                        val past = if (vertical) {
                            v.translationY < -v.height * 0.3f
                        } else {
                            v.translationX > v.width * 0.3f
                        }
                        if (past) {
                            hide(content, v) { spec.onDismiss() }
                        } else {
                            v.animate().translationX(0f).translationY(0f).alpha(1f)
                                .setDuration(ANIM_MS).start()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun hide(content: ViewGroup, card: View, onEnd: () -> Unit) {
        showingId = null
        shownCard = null
        shownParent = null
        val margin = (card.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val animator = card.animate().alpha(0f).setDuration(ANIM_MS)
        // A sideways swipe is already moving; finish it in the same direction rather than snapping
        // back to the default upward exit. Everything else — the action button, an upward flick, the
        // programmatic [dismiss] above — starts centered and leaves upward as before.
        if (card.translationX > card.width * 0.05f) {
            animator.translationX((card.width + margin).toFloat())
        } else {
            animator.translationY(-(card.height + margin).toFloat())
        }
        animator
            .withEndAction {
                content.removeView(card)
                onEnd()
            }
            .start()
    }
}
