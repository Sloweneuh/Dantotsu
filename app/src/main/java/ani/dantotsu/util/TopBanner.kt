package ani.dantotsu.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import ani.dantotsu.R
import ani.dantotsu.databinding.ViewTopBannerBinding
import ani.dantotsu.databinding.ViewTopBannerChipBinding
import ani.dantotsu.settings.SectionCard
import ani.dantotsu.statusBarHeight
import com.google.android.material.color.MaterialColors
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Persistent notices that slide down over whatever screen the user is on.
 *
 * The app kept telling people things with snackbars that they had no realistic chance of reading: a
 * few seconds, at the bottom, on one screen, in the same shape for every unrelated message — and
 * with an action attached, so missing it meant missing the only way to act. Anything the user is
 * genuinely expected to *do* something about belongs here instead: distinct, wherever they are, and
 * gone only when they say so.
 *
 * Several can be pending at once, and they stack behind the most pressing one (the order is
 * [AppNotices]'s), so however many there are they cover no more of the screen than one. The ones
 * behind peek out under it, each a step lower and narrower, with a "+N" chip hung over the edge;
 * tapping either unfolds the stack into a list. Swiping the top one away brings the next up.
 *
 * Not a replacement for snackbars in general. A snackbar is right for "saved", "copied", "no
 * internet" — outcomes that need no response. This is for the ones that do.
 */
object TopBanner {

    private const val TAG = "app_top_banner"
    private const val ANIM_MS = 280L

    /** Cards that peek out under the top one while the stack is folded, like a deck seen side on. */
    private const val PEEK_COUNT = 2
    private const val PEEK_STEP_DP = 6f
    private const val PEEK_INSET_DP = 12f

    /** Between the cards of an unfolded stack. */
    private const val GAP_DP = 8f

    /**
     * The cards' corner radius. A card peeking out is cut off this far below the top card's top
     * edge, so none of it shows through that card's rounded corners or above it.
     */
    private const val CORNER_DP = 24f

    /**
     * @param id distinguishes what a banner is *about*, so a second showing of the same notice
     *   replaces it rather than stacking, and a different one can tell it isn't its own.
     * @param onAction invoked on the UI thread after the banner animates away; null shows no button.
     * @param onDismiss invoked when the user swipes it away without acting.
     * @param onShown invoked once the card can actually be read: on top of the stack, or unfolded
     *   into view. Peeking out from behind another doesn't count.
     */
    data class Spec(
        val id: String,
        val iconRes: Int,
        val title: String,
        val subtitle: String? = null,
        val actionLabel: String? = null,
        val onAction: (Activity) -> Unit = {},
        val onDismiss: () -> Unit = {},
        val onShown: () -> Unit = {},
    )

    private class Entry(val spec: Spec, val card: View)

    /** The notices raised, most pressing first — what follows the user from screen to screen. */
    @Volatile
    private var ids: List<String> = emptyList()

    /**
     * The frame holding the stack's cards, on the content root of the screen it was last raised on.
     *
     * One at a time: following the user means building it again on the next screen, and the copy
     * left behind is taken down as that happens. Leaving earlier copies on activities that were
     * merely paused once meant dismissing the banner took down the card in front of the user and
     * none of the others, so going back a screen showed the notice again, now un-dismissable.
     *
     * Weakly held so a frame left on a finished activity can't keep it alive.
     */
    private var host: WeakReference<FrameLayout>? = null
    private var chip: ViewTopBannerChipBinding? = null

    /** The cards in [host], most pressing first. */
    private val entries = mutableListOf<Entry>()

    /** Whether the stack is unfolded into a list. Kept across screens, like the stack itself. */
    private var expanded = false

    /** Whether this notice has been raised and not yet taken down, wherever its card ended up. */
    fun isShowing(id: String): Boolean = id in ids

    /**
     * Whether exactly this stack is on [activity]'s screen — the question a caller deciding whether
     * to show it has to ask.
     *
     * Not the same question as [isShowing], and the difference is the whole point: the stack is
     * added to one activity's content root, while this object outlives every activity. A rotation
     * destroys the view and builds a new screen without it, and a move to another screen arrives
     * before that screen has a stack of its own. Asking only whether the notices were raised is how
     * a banner once disappeared for good on a rotation, and never followed the user anywhere.
     */
    fun isShowingIn(activity: Activity, ids: List<String>): Boolean {
        if (this.ids != ids) return false
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val frame = host?.get() ?: return false
        return frame.isAttachedToWindow && frame.parent === content
    }

    /**
     * Takes down a banner whose subject has stopped being true — sync switched off underneath a
     * conflict notice, say. Without this the card stays on screen offering an action that can no
     * longer do anything, which reads as the feature being broken rather than off.
     */
    fun dismiss(id: String) {
        if (id !in ids) return
        val entry = entries.firstOrNull { it.spec.id == id }
        // A stack belonging to an activity that has since gone away has nothing to animate; drop
        // the bookkeeping instead, so the next screen isn't told a banner is up that no longer exists.
        if (entry == null || host?.get()?.isAttachedToWindow != true) {
            ids = ids - id
            entries.remove(entry)
            if (ids.isEmpty()) tearDown()
            return
        }
        remove(entry) {}
    }

    /** Shows [specs], most pressing first, as one stack — replacing whatever stack was up. */
    fun show(activity: Activity, specs: List<Spec>) {
        if (specs.isEmpty()) return
        // Touch cards a remote can't reach: the notices wait for the phone interface instead.
        if (activity is ani.dantotsu.tv.TvScreen) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val wanted = specs.map { it.id }
        // Re-showing the stack already on *this* screen would restart its entrance for nothing.
        if (isShowingIn(activity, wanted)) return

        // The notices already up, which are only moving to another screen or making room.
        //
        // Following the user means building the stack again on every activity change and every
        // rotation, because it belongs to the content root it was added to. Sliding it in each time
        // made one persistent notice look like a notice that fired again on every navigation — the
        // same repetitive quality this was built to get away from. A carried-over card is simply
        // already there, which is what a banner that never left should look like.
        val carriedOver = ids.toSet()
        tearDown()
        // An activity change leaves the old view behind with its own content root; clear any stale
        // copy from this one before adding.
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }

        val density = activity.resources.displayMetrics.density
        val side = (12 * density).toInt()
        // Sitting right under the status bar put the card directly over the back/close button
        // most screens place there (commonly a ~48dp toolbar), and on the home screen, over the
        // settings avatar. A standard app-bar height of clearance isn't a guarantee for every
        // screen's layout, but it clears the common case instead of the previous 12dp, which
        // cleared none of them.
        val topClearance = (56 * density).toInt()
        // The whole screen, so cards unfolded or swiped aside still take taps, but neither
        // clickable nor focusable itself: touches that miss every card fall through to the screen.
        val frame = FrameLayout(activity).apply {
            tag = TAG
            clipChildren = false
            clipToPadding = false
            setPadding(side, topInset(content) + topClearance, side, 0)
        }
        content.addView(frame, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // The padding above is the best guess available at this instant, and a banner raised by
        // background work — an AniList outage found by the first query of a cold start — can be
        // built before the window has dispatched a single inset, when the process-global heights
        // are still their startup fallbacks. Correct it from the real insets whenever they arrive,
        // which also covers a rotation or a move into multi-window changing them afterwards.
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            v.updatePadding(top = statusBarTop(insets) + topClearance)
            insets
        }
        ViewCompat.requestApplyInsets(frame)

        specs.mapTo(entries) { Entry(it, buildCard(activity, it)) }
        // Added least pressing first, so each card is drawn over the ones behind it.
        entries.asReversed().forEach { entry ->
            frame.addView(entry.card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            attachGestures(activity, entry)
        }

        val chipBinding = ViewTopBannerChipBinding.inflate(LayoutInflater.from(activity), frame, false)
        // Inset from the card's edge, so it sits on the straight part of the bottom rather than
        // over the rounded corner.
        frame.addView(
            chipBinding.root,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { marginEnd = side }
        )
        chipBinding.root.setOnClickListener { setExpanded(!expanded) }
        chipBinding.root.alpha = 0f
        chipBinding.root.visibility = View.INVISIBLE

        ids = wanted
        host = WeakReference(frame)
        chip = chipBinding
        if (entries.size < 2) expanded = false
        entries.first().spec.onShown()
        if (expanded) entries.forEach { it.spec.onShown() }

        // Posted until layout because every position here comes from the cards' own heights.
        frame.doOnLayout {
            if (host?.get() !== frame) return@doOnLayout
            place(animate = false)
            val lead = entries.first()
            if (lead.spec.id !in carriedOver) {
                val card = lead.card
                card.alpha = 0f
                card.translationY = -(card.height + side).toFloat()
                card.animate().translationY(0f).alpha(1f).setDuration(ANIM_MS).start()
                // Held back until the card it hangs off has mostly arrived.
                chipBinding.root.let { c ->
                    val target = c.alpha
                    c.alpha = 0f
                    c.animate().alpha(target).setStartDelay(ANIM_MS / 2).setDuration(ANIM_MS).start()
                }
            }
            entries.drop(1).forEach { entry ->
                if (entry.spec.id in carriedOver) return@forEach
                val card = entry.card
                val target = card.alpha
                card.alpha = 0f
                card.animate().alpha(target).setDuration(ANIM_MS).start()
            }
        }
    }

    private fun buildCard(activity: Activity, spec: Spec): View {
        val binding = ViewTopBannerBinding.inflate(LayoutInflater.from(activity))
        val card = binding.root
        // The open look of a settings card: a wash of primary over the surface, so a notice stands
        // out from the screen under it, and the icon on a tile tinted to match.
        card.setCardBackgroundColor(SectionCard.fill(card, open = true))
        binding.topBannerIconTile.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.compositeARGBWithAlpha(
                MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary), 36
            )
        )
        binding.topBannerIcon.setImageResource(spec.iconRes)
        binding.topBannerTitle.text = spec.title
        binding.topBannerInfo.isVisible = !spec.subtitle.isNullOrBlank()
        binding.topBannerInfo.text = spec.subtitle
        binding.topBannerAction.isVisible = !spec.actionLabel.isNullOrBlank()
        binding.topBannerAction.text = spec.actionLabel
        return card
    }

    private fun setExpanded(value: Boolean) {
        if (value && entries.size < 2) return
        expanded = value
        if (value) entries.forEach { it.spec.onShown() }
        place(animate = true)
    }

    /**
     * Puts every card, and the chip, where the stack's current shape says it goes — moving them
     * there from wherever they are when [animate], so a swipe, a card leaving and the stack folding
     * all blend into each other.
     */
    private fun place(animate: Boolean) {
        val frame = host?.get() ?: return
        if (entries.isEmpty()) return
        val density = frame.resources.displayMetrics.density
        val step = PEEK_STEP_DP * density
        val inset = PEEK_INSET_DP * density
        val gap = GAP_DP * density
        val width = (frame.width - frame.paddingLeft - frame.paddingRight).coerceAtLeast(1)
        val leadHeight = entries.first().card.height
        var unfoldedTop = 0f

        entries.forEachIndexed { index, entry ->
            val card = entry.card
            val height = card.height
            val level = min(index, PEEK_COUNT)
            // Folded, the cards behind the top one hang off its bottom edge a step apart, and
            // those past the last step wait out of sight behind it.
            val y = when {
                expanded -> unfoldedTop
                index == 0 -> 0f
                else -> leadHeight + level * step - height
            }
            val scale = if (expanded) 1f else 1f - 2f * inset * level / width
            val alpha = if (expanded || index <= PEEK_COUNT) 1f else 0f
            unfoldedTop += height + gap

            // Narrowed about its bottom edge, which is the part left peeking out.
            card.pivotX = card.width / 2f
            card.pivotY = height.toFloat()
            // A card behind another only shows its edge, so its button is out of reach anyway.
            card.importantForAccessibility =
                if (!expanded && index > 0) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            if (alpha > 0f) card.visibility = View.VISIBLE
            if (animate) {
                card.animate()
                    .translationX(0f).translationY(y)
                    .scaleX(scale).scaleY(scale)
                    .alpha(alpha)
                    .setStartDelay(0)
                    .setDuration(ANIM_MS)
                    .setUpdateListener { clip(card, index, density) }
                    .withEndAction {
                        // Hidden as well as see-through, so it takes no taps meant for the one over it.
                        if (alpha == 0f) card.visibility = View.INVISIBLE
                        clip(card, index, density)
                    }
                    .start()
            } else {
                card.animate().cancel()
                card.translationX = 0f
                card.translationY = y
                card.scaleX = scale
                card.scaleY = scale
                card.alpha = alpha
                if (alpha == 0f) card.visibility = View.INVISIBLE
                clip(card, index, density)
            }
        }

        val chipBinding = chip ?: return
        val chipView = chipBinding.root
        val stacked = entries.size - 1
        // The chip keeps its last count while it fades away, rather than reading +0 on the way out.
        if (stacked > 0) {
            val context = chipView.context
            chipBinding.topBannerChipText.text =
                if (expanded) context.getString(R.string.top_banner_less) else "+$stacked"
            chipView.contentDescription =
                if (expanded) context.getString(R.string.top_banner_show_less)
                else context.getString(R.string.top_banner_show_all, entries.size)
        }
        // Hung half over the bottom edge of the last card, so it covers as little as possible of
        // the screen under the stack, and rides that edge as the stack folds and unfolds.
        val bottom = if (expanded) unfoldedTop - gap else leadHeight + min(stacked, PEEK_COUNT) * step
        val chipY = bottom - chipView.height / 2f
        val chipAlpha = if (stacked > 0) 1f else 0f
        val rotation = if (expanded) 180f else 0f
        chipView.isClickable = stacked > 0
        if (chipAlpha > 0f) chipView.visibility = View.VISIBLE
        if (animate) {
            chipView.animate().translationY(chipY).alpha(chipAlpha).setStartDelay(0).setDuration(ANIM_MS)
                .withEndAction { if (chipAlpha == 0f) chipView.visibility = View.INVISIBLE }
                .start()
            chipBinding.topBannerChipIcon.animate().rotation(rotation).setDuration(ANIM_MS).start()
        } else {
            chipView.translationY = chipY
            chipView.alpha = chipAlpha
            if (chipAlpha == 0f) chipView.visibility = View.INVISIBLE
            chipBinding.topBannerChipIcon.rotation = rotation
        }
    }

    /** Cuts off whatever part of a card behind the top one would show above that card's top edge. */
    private fun clip(card: View, index: Int, density: Float) {
        val top = if (index == 0) 0 else (CORNER_DP * density - card.translationY).toInt()
        card.clipBounds = if (top <= 0) null else Rect(0, top, card.width, card.height)
    }

    /**
     * Takes [entry]'s card off the stack and closes the gap — the next card coming up to the top if
     * it was the top one. [onEnd] runs once the card has animated away.
     */
    private fun remove(entry: Entry, onEnd: () -> Unit) {
        val frame = host?.get()
        val wasLead = entries.firstOrNull() === entry
        entries.remove(entry)
        ids = entries.map { it.spec.id }
        if (entries.size < 2) expanded = false

        val card = entry.card
        val margin = frame?.paddingTop ?: 0
        val animator = card.animate().setUpdateListener(null).alpha(0f).setStartDelay(0).setDuration(ANIM_MS)
        // A sideways swipe is already moving; finish it in the same direction rather than snapping
        // back to the default upward exit. The top card — the action button, an upward flick, a
        // programmatic [dismiss] — leaves upward as before; one further down simply fades, rather
        // than crossing the cards above it on its way out.
        if (card.translationX > card.width * 0.05f) {
            animator.translationX((card.width + margin).toFloat())
        } else if (wasLead) {
            animator.translationY(card.translationY - (card.height + margin))
        }
        animator
            .withEndAction {
                (card.parent as? ViewGroup)?.removeView(card)
                // Only if no new stack has been raised in the meantime, which this would take down.
                if (entries.isEmpty() && host?.get() === frame) tearDown()
                onEnd()
            }
            .start()

        if (entries.isNotEmpty()) {
            place(animate = true)
            if (wasLead) entries.first().spec.onShown()
        }
    }

    /** Takes the stack off whatever screen it is on and forgets it. */
    private fun tearDown() {
        host?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        host = null
        chip = null
        entries.clear()
        ids = emptyList()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(activity: Activity, entry: Entry) {
        val card = entry.card
        card.findViewById<View>(R.id.topBannerAction).setOnClickListener {
            if (entry in entries) remove(entry) { entry.spec.onAction(activity) }
        }

        // Flick the top card up, or swipe any card right, to dismiss it — the only way, now that
        // the close button is gone. Deliberately no timeout: being missable is the failure this
        // exists to correct. A card peeking out from behind the top one unfolds the stack instead.
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

            // A gesture the card sits out: an upward flick on a card below the top one, which
            // would drag it across the cards above.
            private var ignored = false

            private fun peeking() = !expanded && entries.indexOf(entry) > 0

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = v.translationX
                        startY = v.translationY
                        dragging = false
                        ignored = false
                        // Must return true: an OnTouchListener that returns false on ACTION_DOWN
                        // never gets the MOVE/UP that follow, which silently killed the swipe
                        // entirely. Safe to claim here regardless — card's own listener (as
                        // opposed to onInterceptTouchEvent) only ever sees touches the action
                        // button didn't already consume, so its taps are unaffected.
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (peeking() || ignored) return true
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                            vertical = abs(dy) >= abs(dx)
                            if (vertical && entries.indexOf(entry) != 0) {
                                ignored = true
                                return true
                            }
                            dragging = true
                        }
                        if (!dragging) return false
                        if (vertical) {
                            v.translationY = startY + min(0f, dy)
                            v.alpha = (1f + (v.translationY - startY) / v.height).coerceIn(0f, 1f)
                        } else {
                            v.translationX = startX + max(0f, dx)
                            v.alpha = (1f - v.translationX / v.width).coerceIn(0f, 1f)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (peeking()) {
                            if (e.actionMasked == MotionEvent.ACTION_UP) setExpanded(true)
                            return true
                        }
                        if (!dragging) return false
                        val past = if (vertical) {
                            v.translationY - startY < -v.height * 0.3f
                        } else {
                            v.translationX > v.width * 0.3f
                        }
                        if (past && entry in entries) {
                            remove(entry) { entry.spec.onDismiss() }
                        } else {
                            v.animate().setUpdateListener(null)
                                .translationX(0f).translationY(startY).alpha(1f)
                                .setStartDelay(0).setDuration(ANIM_MS).start()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    /**
     * How far down the screen the stack has to start to clear the status bar.
     *
     * Prefers what the window actually reports over [statusBarHeight], which is a process-global
     * filled in by whichever activity started first and can still be a resource-table estimate — or
     * plain wrong for this screen, since an immersive activity leaves it at the cutout size. The
     * global is still taken as a floor: with the status bar hidden the live inset is 0, and the
     * cutout it was set from is exactly what a card at the very top would run into.
     */
    private fun statusBarTop(insets: WindowInsetsCompat?): Int {
        val live = insets
            ?.takeIf { it.isVisible(WindowInsetsCompat.Type.statusBars()) }
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        return max(live, statusBarHeight)
    }

    private fun topInset(content: View): Int =
        statusBarTop(ViewCompat.getRootWindowInsets(content))
}
