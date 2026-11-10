package ani.dantotsu.media

import android.widget.ImageView
import android.content.res.ColorStateList
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
    // The cover sets off showing only what the list showed of it (see CoverClip), opening up to
    // the whole cover over the flight.
    setEnterSharedElementCallback(object : SharedElementCallback() {
        private val clipped = mutableListOf<View>()

        override fun onSharedElementStart(
            sharedElementNames: MutableList<String>?,
            sharedElements: MutableList<View>?,
            sharedElementSnapshots: MutableList<View>?,
        ) {
            // Opening, the cut the cover left its list with; going back, the one the list says
            // the cover lands with — this page flies its own copy of it, in its own window above
            // the list, and it would land over the list's header whole.
            val pending = CoverClip.take() ?: return
            sharedElements.orEmpty().filter { it.transitionName == pending.name }.forEach { view ->
                view.clipBounds = pending.fractions.toLocal(view.width, view.height)
                clipped += view
            }
        }

        override fun onSharedElementEnd(
            sharedElementNames: MutableList<String>?,
            sharedElements: MutableList<View>?,
            sharedElementSnapshots: MutableList<View>?,
        ) {
            clipped.forEach { it.clipBounds = null }
            clipped.clear()
        }
    })
}

/**
 * What part of a cover its list actually showed — a row half under the list's header, or under a
 * bar drawn over the list — so its flight starts (and, coming back, lands) cut the same way instead
 * of the hidden part appearing above the header for the length of the flight.
 */
internal object CoverClip {
    /** The visible part of a cover, as fractions of its width and height. */
    data class Fractions(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun toLocal(width: Int, height: Int) = android.graphics.Rect(
            (left * width).toInt(), (top * height).toInt(),
            (right * width).toInt(), (bottom * height).toInt(),
        )
    }

    data class Pending(val name: String, val fractions: Fractions)

    /** Set by the list as a cover leaves it, taken by the page it flies to. */
    @Volatile
    private var pending: Pending? = null

    /**
     * Hands what [view]'s list shows of it to the page on the other end of the flight — the page
     * it leaves for, or the page coming back, which flies its own copy of the cover to the row —
     * and returns it, null when the list shows all of [view].
     */
    fun offer(view: View, viewport: android.graphics.Rect): Fractions? {
        val fractions = fractionsIn(view, viewport)
        pending = view.transitionName?.let { name -> fractions?.let { Pending(name, it) } }
        return fractions
    }

    fun take(): Pending? = pending.also { pending = null }

    /** Null when [viewport] shows all of [view]: nothing to clip. */
    fun fractionsIn(view: View, viewport: android.graphics.Rect): Fractions? {
        if (view.width <= 0 || view.height <= 0) return null
        val at = IntArray(2).also(view::getLocationInWindow)
        val bounds = android.graphics.Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
        if (viewport.contains(bounds)) return null
        val shown = android.graphics.Rect(bounds)
        if (!shown.intersect(viewport)) shown.set(bounds.left, bounds.top, bounds.left, bounds.top)
        return Fractions(
            (shown.left - bounds.left) / view.width.toFloat(),
            (shown.top - bounds.top) / view.height.toFloat(),
            (shown.right - bounds.left) / view.width.toFloat(),
            (shown.bottom - bounds.top) / view.height.toFloat(),
        )
    }

    /**
     * The part of the window [view] can be seen in, in window coordinates: every clipping ancestor's
     * bounds (inside its padding when it clips to it), less any bar drawn over it — a later sibling
     * of an ancestor spanning the whole width, like a header or a bottom bar laid over a list.
     */
    fun viewport(view: View): android.graphics.Rect {
        val root = view.rootView
        val rect = android.graphics.Rect(0, 0, root.width, root.height)
        val at = IntArray(2)
        var child = view
        var parent = view.parent as? ViewGroup
        while (parent != null) {
            parent.getLocationInWindow(at)
            if (parent.clipChildren) {
                val clip = if (parent.clipToPadding) android.graphics.Rect(
                    at[0] + parent.paddingLeft, at[1] + parent.paddingTop,
                    at[0] + parent.width - parent.paddingRight, at[1] + parent.height - parent.paddingBottom,
                ) else android.graphics.Rect(at[0], at[1], at[0] + parent.width, at[1] + parent.height)
                if (!rect.intersect(clip)) return android.graphics.Rect()
            }
            val index = parent.indexOfChild(child)
            for (i in index + 1 until parent.childCount) {
                val bar = parent.getChildAt(i)
                if (!bar.isShown || bar.alpha == 0f || bar.height <= 0 || bar.height >= rect.height() / 2) continue
                bar.getLocationInWindow(at)
                val top = at[1]
                val barRect = android.graphics.Rect(at[0], top, at[0] + bar.width, top + bar.height)
                if (barRect.left > rect.left || barRect.right < rect.right) continue
                if (!android.graphics.Rect.intersects(barRect, rect)) continue
                if (barRect.centerY() < rect.centerY()) rect.top = maxOf(rect.top, barRect.bottom)
                else rect.bottom = minOf(rect.bottom, barRect.top)
            }
            child = parent
            parent = parent.parent as? ViewGroup
        }
        return rect
    }
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
 * The tint an icon had on the page it flew from, handed to the page it lands on so the colour
 * blends over the flight instead of switching the moment it sets off.
 *
 * A row icon on the main settings page is primary-tinted; the same icon in a page's header is
 * on-background. Going out, the launching row records its tint ([forward]) and the page blends its
 * header icon from it. Coming back the page blends that same icon into the row's tint itself: it is
 * the one seen on the way home, the row icon staying hidden until it lands.
 */
object SharedIconTint {
    @Volatile
    var forward: Int? = null

    fun takeForward(): Int? = forward.also { forward = null }
}

/** Blends [icon]'s tint from [from] to [to] over a cover flight's length. */
fun blendTint(icon: ImageView, from: Int, to: ColorStateList?) {
    val target = to?.defaultColor ?: run {
        icon.imageTintList = to
        return
    }
    if (!PrefManager.getVal<Boolean>(PrefName.LayoutAnimations)) {
        icon.imageTintList = to
        return
    }
    val argb = android.animation.ArgbEvaluator()
    android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = coverFlightMs()
        interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        addUpdateListener {
            icon.imageTintList = ColorStateList.valueOf(argb.evaluate(it.animatedFraction, from, target) as Int)
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                icon.imageTintList = to
            }
        })
    }.start()
}

/** How long a cover flight takes at the current animation speed. */
fun coverFlightMs(): Long =
    (COVER_TRANSITION_MS * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong()

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

    /**
     * Activities that went behind another one, so the next shared-element start they see is a
     * flight coming home. Told apart this way rather than by a flag set as a flight leaves: the
     * framework calls the same start on the way out too, right after capturing the flight, which
     * used up such a flag before the return — the lift then happened too early and the return's
     * tint blend never ran, the row icon only taking its colour once it had landed.
     */
    private val returning = WeakHashMap<Activity, Boolean>()

    /** Covers cut while they left this activity ([CoverClip.offer]), made whole once it's hidden. */
    private val leftClipped = WeakHashMap<Activity, MutableList<View>>()

    private fun unclipLeft(activity: Activity) {
        leftClipped.remove(activity)?.forEach { it.clipBounds = null }
    }

    /** Counts each activity's stops, so a clear scheduled for one visit can't undo a later one. */
    private val stops = WeakHashMap<Activity, Int>()

    /**
     * Lets a returning shared element fly above the views around it, until it lands.
     *
     * Going out, the framework draws the flying view in an overlay above everything. Coming back
     * it doesn't: the view being landed on is animated where it lives, so it stayed clipped to its
     * list's bounds and faded by the list's edge — passing "behind" the search bar and header on
     * the way to its row. Clipping and fading edges are switched off from the view up to the
     * window's content for the flight; the returned function puts them back.
     */
    private fun liftOutOfClipping(activity: Activity, view: View): () -> Unit {
        val content = activity.findViewById<View>(android.R.id.content)
        val undo = mutableListOf<() -> Unit>()
        var parent = view.parent as? ViewGroup
        while (parent != null) {
            val group = parent
            val clip = group.clipChildren to group.clipToPadding
            group.clipChildren = false
            group.clipToPadding = false
            val fading = group.isVerticalFadingEdgeEnabled to group.isHorizontalFadingEdgeEnabled
            group.isVerticalFadingEdgeEnabled = false
            group.isHorizontalFadingEdgeEnabled = false
            undo += {
                group.clipChildren = clip.first
                group.clipToPadding = clip.second
                group.isVerticalFadingEdgeEnabled = fading.first
                group.isHorizontalFadingEdgeEnabled = fading.second
            }
            if (group === content) break
            parent = group.parent as? ViewGroup
        }
        return { undo.forEach { it() } }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.setExitSharedElementCallback(object : SharedElementCallback() {
            /** What the list shows of each cover a page is coming back to, from the destination capture. */
            private val landingCuts = mutableMapOf<View, CoverClip.Fractions>()

            /** Covers landing clipped to their viewport, unclipped once landed. */
            private val clipped = mutableListOf<View>()

            override fun onCaptureSharedElementSnapshot(
                sharedElement: View,
                viewToGlobalMatrix: Matrix,
                screenBounds: RectF,
            ): Parcelable? {
                hideBadges(activity, sharedElement)
                // The snapshot first: the page gets the whole cover, and cuts it itself.
                val snapshot = super.onCaptureSharedElementSnapshot(
                    sharedElement, viewToGlobalMatrix, screenBounds,
                )
                val fractions = CoverClip.offer(sharedElement, CoverClip.viewport(sharedElement))
                    ?: return snapshot
                if (returning[activity] == true) {
                    // Captured to tell a page coming back where its cover lands: this list's own
                    // cover flies cut the same way from start to end, so it has nothing left to
                    // cut once the page is gone.
                    landingCuts[sharedElement] = fractions
                } else {
                    // Leaving: while the page holds its first frame, the framework draws this
                    // cover in the window's overlay where it sat — above the header, whole.
                    sharedElement.clipBounds = fractions.toLocal(sharedElement.width, sharedElement.height)
                    leftClipped.getOrPut(activity) { mutableListOf() } += sharedElement
                }
                return snapshot
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
                if (returning.remove(activity) != true) return
                unclipLeft(activity)
                val elements = sharedElements.orEmpty()
                elements.forEach { view ->
                    val cut = landingCuts[view] ?: return@forEach
                    view.clipBounds = cut.toLocal(view.width, view.height)
                    clipped += view
                }
                val lifted = mutableListOf<() -> Unit>()
                elements.forEach { lifted += liftOutOfClipping(activity, it) }
                activity.window.sharedElementReenterTransition?.addListener(
                    object : TransitionListenerAdapter() {
                        override fun onTransitionEnd(transition: Transition) = landed(transition)
                        override fun onTransitionCancel(transition: Transition) = landed(transition)

                        private fun landed(transition: Transition) {
                            lifted.forEach { it() }
                            // Not unclipped yet: the framework keeps drawing the cover in the
                            // window's overlay, above the header, for a moment after this — whole,
                            // had its cut gone now. Cut to what the list shows, it looks the same
                            // as the list's own clipping for as long as the row stays put.
                            clipped.forEach { keepClipUntilMoved(it) }
                            clipped.clear()
                            restore(activity)
                        }
                    }
                )
            }

            // The covers are back at their rows here, before the flight's end state is captured:
            // cut to what the list shows of them, as they were at the start, so they land under
            // the header, not over it.
            override fun onSharedElementEnd(
                sharedElementNames: MutableList<String>?,
                sharedElements: MutableList<View>?,
                sharedElementSnapshots: MutableList<View>?,
            ) {
                if (landingCuts.isEmpty()) return
                sharedElements.orEmpty().forEach { view ->
                    val cut = landingCuts[view] ?: return@forEach
                    view.clipBounds = cut.toLocal(view.width, view.height)
                }
                landingCuts.clear()
            }
        })
    }

    override fun onActivityStopped(activity: Activity) {
        unclipLeft(activity)
        stops[activity] = (stops[activity] ?: 0) + 1
        returning[activity] = true
    }

    override fun onActivityResumed(activity: Activity) {
        // A return's start arrives within the first frames back. Stopped for any other reason —
        // the app sent to the background — the flag would otherwise outlive the visit and mark the
        // next launch's start as a return. Only this visit's flag is cleared: a page opened within
        // the delay stops this activity again, and an unguarded clear then wiped the flag that stop
        // had just set, so the return went unrecognised and its tint never blended in flight.
        // Back without having been stopped (the page never showed): the cover is still cut.
        unclipLeft(activity)
        val visit = stops[activity]
        activity.window.decorView.postDelayed({
            if (stops[activity] == visit) returning.remove(activity)
        }, FALLBACK_RESTORE_MS)
        if (away[activity].isNullOrEmpty()) return
        activity.window.decorView.postDelayed({ restore(activity) }, FALLBACK_RESTORE_MS)
    }

    override fun onActivityDestroyed(activity: Activity) {
        away.remove(activity)
        leftClipped.remove(activity)
        stops.remove(activity)
        returning.remove(activity)
    }

    /**
     * Drops [view]'s landing cut once the row can move — the list scrolls, or the row leaves it —
     * or once the overlay has long let go of it.
     */
    private fun keepClipUntilMoved(view: View) {
        val list = listItemOf(view)?.parent as? RecyclerView
        var done = false
        var unclip: () -> Unit = {}
        val onScroll = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0 || dy != 0) unclip()
            }
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = unclip()
        }
        unclip = {
            if (!done) {
                done = true
                view.clipBounds = null
                list?.removeOnScrollListener(onScroll)
                view.removeOnAttachStateChangeListener(detach)
            }
        }
        list?.addOnScrollListener(onScroll)
        view.addOnAttachStateChangeListener(detach)
        view.postDelayed({ unclip() }, LANDED_CLIP_MS)
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
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    private const val BADGE_FADE_MS = 120L

    /** How long a landed cover keeps its cut if nothing moves: well past the overlay letting go. */
    private const val LANDED_CLIP_MS = 3000L

    /** After the activity returns, by which time any cover flight has long landed. */
    private const val FALLBACK_RESTORE_MS = 1200L

    /** How far up from the cover image an item's root can be. */
    private const val MAX_ITEM_DEPTH = 6
}
