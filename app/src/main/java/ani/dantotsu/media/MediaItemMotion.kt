package ani.dantotsu.media

import android.annotation.SuppressLint
import android.app.Activity
import android.transition.ArcMotion
import android.transition.ChangeBounds
import android.transition.ChangeClipBounds
import android.transition.ChangeImageTransform
import android.transition.ChangeTransform
import android.transition.TransitionSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.databinding.ItemUnreadChapterBinding
import ani.dantotsu.databinding.ItemUnreleasedEpisodeBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/*
 * Touch feedback for the media cards (item_media_compact / item_media_large).
 *
 * Applied where a binding is inflated rather than in one adapter, because the two layouts are
 * inflated by a good dozen of them — the home rows, the MangaUpdates lists, every tracker's search
 * results — and feedback that only MediaAdaptor's items had read as broken everywhere else.
 */

/** The cover gives under the finger; the title below it stays put. */
fun ItemMediaCompactBinding.withPressFeedback(): ItemMediaCompactBinding = apply {
    pressFeedback(root, itemCompactCard, 0.94f)
}

/** The home screen's unread row: the same cover card under another layout. */
fun ItemUnreadChapterBinding.withPressFeedback(): ItemUnreadChapterBinding = apply {
    pressFeedback(root, itemCompactCard, 0.94f)
}

/** The home screen's unreleased-episodes row: the same cover card under another layout. */
fun ItemUnreleasedEpisodeBinding.withPressFeedback(): ItemUnreleasedEpisodeBinding = apply {
    pressFeedback(root, itemCompactCard, 0.94f)
}

/** The whole card, gently: it's wide, and the cover alone moving would look detached. */
fun ItemMediaLargeBinding.withPressFeedback(): ItemMediaLargeBinding = apply {
    pressFeedback(root, root, 0.98f)
}

/**
 * How a tapped cover travels into a media page and back: along a gentle arc rather than a straight
 * line, easing out of the list and settling into place. Android's default is a quick straight move
 * on a generic curve, which read as a jump on a cover that also changes size. Follows the app's
 * animation speed; with animations off the cover simply appears where it lands.
 *
 * Call from a media page's onCreate, before its postponed enter transition starts.
 */
fun Activity.useCoverTransition() {
    val speed = PrefManager.getVal<Float>(PrefName.AnimationSpeed)
    val animate = PrefManager.getVal<Boolean>(PrefName.LayoutAnimations)
    fun cover() = TransitionSet().apply {
        addTransition(ChangeBounds())
        addTransition(ChangeTransform())
        addTransition(ChangeClipBounds())
        addTransition(ChangeImageTransform())
        setPathMotion(ArcMotion().apply { maximumAngle = 50f })
        duration = if (animate) (COVER_TRANSITION_MS * speed).toLong() else 0L
        interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
    window.sharedElementEnterTransition = cover()
    window.sharedElementReturnTransition = cover()
}

/**
 * Holds the cover's flight back from a media page until this screen's list has drawn again.
 *
 * Call from [Activity.onRestart] — not onActivityReenter, which only runs when the returning page
 * sets a result, and the media pages don't. The return transition looks the cover up by name the
 * moment it starts, and a list that changed while the page was open — the user list swaps to its
 * merged adapter when the MangaUpdates entries land — has not built its new rows yet then. The
 * cover isn't found and the flight is skipped. Waiting one frame lets the rows exist; the timeout
 * is only there so a screen that never draws can't hold the transition forever.
 */
fun Activity.awaitListForReturn() {
    postponeEnterTransition()
    val content = window.decorView
    // A row that changed place while the page was open is drawn, on that first frame, back where
    // it was — the item animator starts it there to slide it to its new spot — and the cover flew
    // to that old spot. The lists lay out without their animators until the cover has landed, so
    // every row is drawn where it actually is.
    val lists = buildList { collectLists(content, this) }
    val animators = lists.map { it.itemAnimator }
    lists.forEach { it.itemAnimator = null }
    content.postDelayed({
        lists.forEachIndexed { i, list -> if (list.itemAnimator == null) list.itemAnimator = animators[i] }
    }, RESTORE_ANIMATORS_MS)
    var started = false
    fun start() {
        if (started) return
        started = true
        startPostponedEnterTransition()
    }
    content.viewTreeObserver.addOnPreDrawListener(object :
        android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            content.viewTreeObserver.removeOnPreDrawListener(this)
            start()
            return true
        }
    })
    content.postDelayed({ start() }, 500)
}

private fun collectLists(view: View, into: MutableList<RecyclerView>) {
    if (view is RecyclerView) into += view
    if (view is ViewGroup) for (i in 0 until view.childCount) collectLists(view.getChildAt(i), into)
}

/** Long enough for the cover to have landed, at any animation speed a user would pick. */
private const val RESTORE_ANIMATORS_MS = 1500L

/** The cover's flight, at animation speed 1. */
private const val COVER_TRANSITION_MS = 380L

/**
 * [target] shrinks to [scale] while [itemView] is pressed and springs back on release. Never
 * consumes the touch, so the item's click and long click work as before. Off with the layout
 * animations setting.
 */
@SuppressLint("ClickableViewAccessibility")
private fun pressFeedback(itemView: View, target: View, scale: Float) {
    itemView.setOnTouchListener { _, event ->
        if (!PrefManager.getVal<Boolean>(PrefName.LayoutAnimations)) return@setOnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> target.animate().scaleX(scale).scaleY(scale)
                .setDuration(120).setInterpolator(DecelerateInterpolator()).start()

            // A tap opens the media page straight away, and its cover transition starts from
            // wherever this cover is at that instant — springing back from pressed, it set off
            // small and grew on the way. So a tap snaps it back first; only a touch that turned
            // into a scroll gets the spring.
            MotionEvent.ACTION_UP -> {
                target.animate().cancel()
                target.scaleX = 1f
                target.scaleY = 1f
            }

            MotionEvent.ACTION_CANCEL -> target.animate()
                .scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }
        false
    }
}
