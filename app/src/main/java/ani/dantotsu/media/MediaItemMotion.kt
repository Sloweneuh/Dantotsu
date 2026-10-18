package ani.dantotsu.media

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.SharedElementCallback
import android.transition.Transition
import android.transition.TransitionListenerAdapter
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.os.Parcelable
import androidx.core.view.isVisible
import ani.dantotsu.R
import java.util.WeakHashMap
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
 * The badges drawn over a media cover — score, source, language, the "ongoing" dot — found from
 * anywhere inside the item. Each layout carries some of them, under the same ids.
 */
internal fun coverBadges(itemView: View): List<View> =
    BADGE_IDS.mapNotNull { id -> itemView.findViewById<View?>(id) }.filter { it.isVisible }

private val BADGE_IDS: List<Int> = listOf(
    R.id.itemCompactScoreBG,
    R.id.itemCompactSourceBadge,
    R.id.itemCompactLanguageBG,
    R.id.itemCompactOngoing,
)

private fun View.isInside(ancestor: View): Boolean {
    var parent = parent as? View
    while (parent != null) {
        if (parent === ancestor) return true
        parent = parent.parent as? View
    }
    return false
}

/**
 * [target] shrinks to [scale] while [itemView] is pressed and springs back on release. Never
 * consumes the touch, so the item's click and long click work as before. Off with the layout
 * animations setting.
 *
 * Badges sitting outside [target] — the "ongoing" dot hangs off the cover's corner from the
 * card's parent — shrink with it, about the cover's centre rather than their own, so they stay
 * pinned to the corner instead of being left floating where the cover's edge used to be.
 */
@SuppressLint("ClickableViewAccessibility")
private fun pressFeedback(itemView: View, target: View, scale: Float) {
    var riders: List<View> = emptyList()
    itemView.setOnTouchListener { _, event ->
        if (!PrefManager.getVal<Boolean>(PrefName.LayoutAnimations)) return@setOnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                riders = coverBadges(itemView).filter { it !== target && !it.isInside(target) }
                val centre = IntArray(2).also(target::getLocationInWindow)
                    .let { (x, y) -> (x + target.width / 2f) to (y + target.height / 2f) }
                riders.forEach { badge ->
                    val at = IntArray(2).also(badge::getLocationInWindow)
                    badge.pivotX = centre.first - at[0]
                    badge.pivotY = centre.second - at[1]
                }
                (riders + target).forEach {
                    it.animate().scaleX(scale).scaleY(scale)
                        .setDuration(120).setInterpolator(DecelerateInterpolator()).start()
                }
            }

            // A tap opens the media page straight away, and its cover transition starts from
            // wherever this cover is at that instant — springing back from pressed, it set off
            // small and grew on the way. So a tap snaps it back first; only a touch that turned
            // into a scroll gets the spring.
            MotionEvent.ACTION_UP -> (riders + target).forEach {
                it.animate().cancel()
                it.scaleX = 1f
                it.scaleY = 1f
            }

            MotionEvent.ACTION_CANCEL -> (riders + target).forEach {
                it.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }
        }
        false
    }
}

/**
 * Keeps a cover's badges with the cover through its flight to a media page and back.
 *
 * Only the cover image travels; its badges are separate views and used to stay behind in the
 * list, sitting on the empty card for as long as the page was open, with the returning cover then
 * landing underneath them. Now they fade as the cover leaves and fade back once it has landed.
 *
 * Installed once for every activity, since any of them can host a list a cover leaves from: an
 * exit callback is told about each shared element as it's captured for the flight, which is the
 * moment its badges go. Coming back, they return as the activity's re-enter transition ends —
 * or, should that never run (the row was recycled, animations are off), a little after the
 * activity is back in front, so a badge can never be left hidden.
 */
object CoverBadgeTransitions : Application.ActivityLifecycleCallbacks {
    /** Badges hidden for a cover that's away, per host activity, with the alpha to restore. */
    private val away = WeakHashMap<Activity, MutableMap<View, Float>>()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onCaptureSharedElementSnapshot(
                sharedElement: View,
                viewToGlobalMatrix: Matrix,
                screenBounds: RectF,
            ): Parcelable? {
                hideBadges(activity, sharedElement)
                return super.onCaptureSharedElementSnapshot(
                    sharedElement, viewToGlobalMatrix, screenBounds,
                )
            }

            // On the way back this runs before the framework copies the re-enter transition for
            // the flight, and a copy shares its original's listeners — so a listener added now
            // hears the copy end, the moment the cover lands. The transition reports no duration
            // of its own (its parts carry theirs) and starts only after a postponed first frame,
            // so a timer guessed from it brought the badges back well after the cover.
            override fun onSharedElementStart(
                sharedElementNames: MutableList<String>?,
                sharedElements: MutableList<View>?,
                sharedElementSnapshots: MutableList<View>?,
            ) {
                if (away[activity].isNullOrEmpty()) return
                activity.window.sharedElementReenterTransition?.addListener(
                    object : TransitionListenerAdapter() {
                        override fun onTransitionEnd(transition: Transition) {
                            transition.removeListener(this)
                            restore(activity)
                        }

                        override fun onTransitionCancel(transition: Transition) {
                            transition.removeListener(this)
                            restore(activity)
                        }
                    }
                )
            }
        })
    }

    override fun onActivityResumed(activity: Activity) {
        if (away[activity].isNullOrEmpty()) return
        activity.window.decorView.postDelayed({ restore(activity) }, FALLBACK_RESTORE_MS)
    }

    override fun onActivityDestroyed(activity: Activity) {
        away.remove(activity)
    }

    /** Fades out the badges of the list item [cover] belongs to, if it belongs to one. */
    private fun hideBadges(activity: Activity, cover: View) {
        val item = listItemOf(cover) ?: return
        val badges = coverBadges(item)
        if (badges.isEmpty()) return
        val saved = away.getOrPut(activity) { mutableMapOf() }
        badges.forEach { badge ->
            saved.getOrPut(badge) { badge.alpha }
            badge.animate().alpha(0f).setDuration(BADGE_FADE_MS).start()
        }
    }

    private fun restore(activity: Activity) {
        val saved = away.remove(activity) ?: return
        val speed = PrefManager.getVal<Float>(PrefName.AnimationSpeed)
        saved.forEach { (badge, alpha) ->
            badge.animate().alpha(alpha).setDuration((BADGE_FADE_MS * 1.5f * speed).toLong())
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /**
     * The row [view] sits in: the ancestor whose parent is a RecyclerView. Badges are only looked
     * for inside it, so a cover can never take a neighbouring row's badges with it. Null for a
     * cover that isn't in a list (a media page's own header), which has none of these badges.
     */
    private fun listItemOf(view: View): View? {
        var child: View = view
        repeat(MAX_ITEM_DEPTH) {
            val parent = child.parent as? View ?: return null
            if (parent is RecyclerView) return child
            child = parent
        }
        return null
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    private const val BADGE_FADE_MS = 120L

    /** After the activity returns, by which time any cover flight has long landed. */
    private const val FALLBACK_RESTORE_MS = 1200L

    /** How far up from the cover image an item's root can be. */
    private const val MAX_ITEM_DEPTH = 6
}
