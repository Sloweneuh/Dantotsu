package ani.dantotsu.media.anime

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import ani.dantotsu.FileUrl
import ani.dantotsu.R
import ani.dantotsu.loadImage
import ani.dantotsu.util.animationsEnabled
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * The "Next episode in 5" card autoplay shows before moving on, so the jump can be stopped or
 * taken early. Started at the end of an episode, or at the start of its ending when the player is
 * set to skip outros — over the ending as it plays, which Cancel leaves playing.
 */
class PlayerNextCountdown(
    private val card: MaterialCardView,
    private val content: ViewGroup,
    private val countdownText: TextView,
    private val nextText: TextView,
    private val thumb: ImageView,
    private val scrim: View,
    private val cancel: MaterialButton,
    private val playNow: MaterialButton,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var remaining = 0
    private var onPlay: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    /** Up, as far as the player is concerned — on screen, or held back while in [inPip]. */
    val isShowing get() = active

    private var active = false

    /** Fading out after [hide]; it counts as gone already, and [start] cuts the fade short. */
    private var hiding = false

    /**
     * Picture-in-picture: the window is too small for the card and its buttons can't be tapped,
     * so it is kept off screen — its countdown still running — and comes back on leaving.
     */
    var inPip = false
        set(value) {
            if (field == value) return
            field = value
            if (!active || hiding) return
            card.animate().cancel()
            card.alpha = 1f
            card.isVisible = !value
            // Re-measured for the window it is back in: a centred card grows to fit it.
            if (!value) place(isCentered)
        }

    /** Showing and counting down — as opposed to the plain "Up next" offer. */
    var isCounting = false
        private set

    /** In the middle of the screen, where it goes once the episode has ended. */
    var isCentered = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (remaining <= 0) {
                fire()
                return
            }
            countdownText.text = card.context.getString(R.string.next_episode_in, remaining)
            remaining--
            handler.postDelayed(this, 1000)
        }
    }

    init {
        cancel.setOnClickListener {
            val then = onCancel
            hide()
            then?.invoke()
        }
        playNow.setOnClickListener { fire() }
    }

    /** Where the layout puts the card, taken on first use so [start] can go back to it. */
    private var cornerGravity: Int? = null
    private var cornerMargins: IntArray? = null

    /**
     * Counts [seconds] down over [nextThumb] when there is one, then runs [play]. [cancelled] runs
     * when the user stops it. [centered]: once the episode has ended there is nothing left to
     * watch, so the card takes the middle of the screen; over an ending still playing it stays in
     * its corner. [seconds] null: no countdown, just an "Up next" offer that waits for Play now.
     */
    fun start(
        nextLabel: String,
        nextThumb: FileUrl? = null,
        centered: Boolean = false,
        seconds: Int? = SECONDS,
        cancelled: (() -> Unit)? = null,
        play: () -> Unit,
    ) {
        handler.removeCallbacks(tick)
        if (hiding) finishHide()
        active = true
        val appearing = !card.isVisible
        onPlay = play
        onCancel = cancelled
        nextText.text = nextLabel
        val hasThumb = !nextThumb?.url.isNullOrBlank()
        thumb.isVisible = hasThumb
        scrim.isVisible = hasThumb
        if (hasThumb) thumb.loadImage(nextThumb) else thumb.setImageDrawable(null)
        // An "Up next" card already over the ending glides to the middle as the episode ends,
        // rather than jumping there; a seek back into the ending sends it back the same way.
        val backToCorner = card.isVisible && isCentered && !centered && animationsEnabled
        val fromCx = card.left + card.width / 2f + card.translationX
        val fromCy = card.top + card.height / 2f + card.translationY
        val fromSize = size * card.scaleX
        if (card.isVisible && animationsEnabled && !backToCorner) {
            (card.parent as? ViewGroup)?.let { parent ->
                TransitionManager.beginDelayedTransition(
                    parent,
                    ChangeBounds().apply {
                        duration = 350
                        interpolator = FastOutSlowInInterpolator()
                    },
                )
            }
        }
        place(centered)
        if (backToCorner) {
            // Laid out small in the corner, then drawn first where and as large as it was, and
            // eased into place: the text is never stretched, only the whole card moves.
            card.doOnPreDraw {
                card.translationX = fromCx - (card.left + card.width / 2f)
                card.translationY = fromCy - (card.top + card.height / 2f)
                // Relative to the corner size it is now laid out at.
                card.scaleX = fromSize / size
                card.scaleY = fromSize / size
                card.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(350).setInterpolator(FastOutSlowInInterpolator()).start()
            }
        }
        card.isVisible = !inPip
        if (appearing && animationsEnabled && !inPip) {
            // Fades in; in the corner it also grows in a little. A centred one grows on its own,
            // from place(), and that scale animation runs alongside this fade.
            card.alpha = 0f
            if (!centered) {
                card.scaleX = APPEAR_SCALE
                card.scaleY = APPEAR_SCALE
                card.animate().scaleX(1f).scaleY(1f)
            }
            card.animate().alpha(1f).setDuration(220)
                .setInterpolator(FastOutSlowInInterpolator()).start()
        }
        if (seconds == null) {
            isCounting = false
            countdownText.setText(R.string.up_next)
            return
        }
        isCounting = true
        remaining = seconds
        tick.run()
    }

    private fun place(centered: Boolean) {
        isCentered = centered
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        val gravity = cornerGravity ?: lp.gravity.also { cornerGravity = it }
        val margins = cornerMargins
            ?: intArrayOf(lp.marginStart, lp.topMargin, lp.marginEnd, lp.bottomMargin).also { cornerMargins = it }
        if (centered) {
            lp.gravity = Gravity.CENTER
            // Start/end too: setMargins leaves the layout's relative margins in place.
            lp.setMargins(0, 0, 0, 0)
            lp.marginStart = 0
            lp.marginEnd = 0
        } else {
            lp.gravity = gravity
            lp.marginStart = margins[0]
            lp.topMargin = margins[1]
            lp.marginEnd = margins[2]
            lp.bottomMargin = cornerBottom ?: margins[3]
        }
        bottomAnim?.cancel()
        appliedBottom = if (centered) -1 else lp.bottomMargin
        card.layoutParams = lp
        if (!centered) {
            card.animate().cancel()
            card.translationX = 0f
            card.translationY = 0f
            card.scaleX = 1f
            card.scaleY = 1f
            applySize(cornerSize())
            return
        }
        // Centred, there is a whole screen for it: grown to about [CENTERED_WIDTH] of the width,
        // which on a large display is well past its corner size. Measured after layout, once its
        // width at the current size is known.
        card.post {
            val parentWidth = (card.parent as? View)?.width ?: 0
            if (card.width <= 0 || parentWidth <= 0) return@post
            val natural = card.width / size
            val target = (parentWidth * CENTERED_WIDTH / natural).coerceIn(1f, MAX_SCALE)
            if (target == size) return@post
            if (animationsEnabled && card.isVisible) {
                // The growth plays as a scale, which is cheap to animate, and lands as a real
                // re-layout at the new size: a card left scaled stretches its text past the
                // buttons' edges and its icon out of its pixels.
                val ratio = target / size
                card.animate().scaleX(ratio).scaleY(ratio).setDuration(350)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .withEndAction {
                        applySize(target)
                        card.scaleX = 1f
                        card.scaleY = 1f
                    }.start()
            } else {
                applySize(target)
            }
        }
    }

    /** The corner's bottom margin [avoid] last worked out; null until it has run. */
    private var cornerBottom: Int? = null
    private var appliedBottom = -1
    private var bottomAnim: ValueAnimator? = null
    private val location = IntArray(2)

    /**
     * Keeps the corner card clear of whichever of [obstacles] are on screen — the controls' bottom
     * bar, the skip-timestamp button — sitting just above the highest, and back down in the
     * corner once they go. Called on every layout pass of the player, which is when they come and
     * go; cheap when nothing moved.
     */
    fun avoid(obstacles: List<View?>) {
        val parent = card.parent as? View ?: return
        if (parent.height == 0) return
        parent.getLocationInWindow(location)
        val parentBottom = location[1] + parent.height
        var lift = 0
        for (view in obstacles) {
            if (view == null || !view.isShown || view.height == 0 || view.alpha == 0f) continue
            view.getLocationInWindow(location)
            lift = maxOf(lift, parentBottom - location[1])
        }
        val base = cornerMargins?.get(3) ?: (card.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: 0
        val target = if (lift > 0) lift + base else base
        cornerBottom = target
        if (!active || isCentered || target == appliedBottom) return
        appliedBottom = target
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        bottomAnim?.cancel()
        if (!animationsEnabled || !card.isVisible) {
            lp.bottomMargin = target
            card.layoutParams = lp
            return
        }
        bottomAnim = ValueAnimator.ofInt(lp.bottomMargin, target).apply {
            duration = 200
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                lp.bottomMargin = it.animatedValue as Int
                card.layoutParams = lp
            }
            start()
        }
    }

    /**
     * The corner card's size. The layout's own suits a large window; a phone in landscape is only
     * about 400dp tall, where that same card covered a third of the picture, so it is shrunk with
     * the screen's shorter side.
     */
    private fun cornerSize(): Float {
        val dm = card.resources.displayMetrics
        val shortSideDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
        return (shortSideDp / FULL_SIZE_SHORT_SIDE_DP).coerceIn(MIN_CORNER_SIZE, 1f)
    }

    /** The size [applySize] last set, as a multiple of the layout's own. */
    private var size = 1f

    /** The layout's own dimensions, read before anything is resized. */
    private val base by lazy {
        val row = playNow.layoutParams as ViewGroup.MarginLayoutParams
        val rowLp = (playNow.parent as View).layoutParams as ViewGroup.MarginLayoutParams
        floatArrayOf(
            countdownText.textSize, nextText.textSize, cancel.textSize, playNow.textSize,
            nextText.maxWidth.toFloat(), content.paddingStart.toFloat(), content.paddingTop.toFloat(),
            content.minimumWidth.toFloat(), playNow.iconSize.toFloat(), playNow.iconPadding.toFloat(),
            playNow.minHeight.toFloat(), cancel.minHeight.toFloat(), playNow.paddingStart.toFloat(),
            playNow.paddingTop.toFloat(), cancel.paddingStart.toFloat(), cancel.paddingTop.toFloat(),
            row.marginStart.toFloat(), rowLp.topMargin.toFloat(), card.radius,
            playNow.insetTop.toFloat(), cancel.insetTop.toFloat(),
        )
    }

    /** Lays the card out at [factor] times the layout's size: text, icon, padding, corners. */
    private fun applySize(factor: Float) {
        if (factor == size) return
        val b = base
        fun px(i: Int) = (b[i] * factor).toInt()
        countdownText.setTextSize(TypedValue.COMPLEX_UNIT_PX, b[0] * factor)
        nextText.setTextSize(TypedValue.COMPLEX_UNIT_PX, b[1] * factor)
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, b[2] * factor)
        playNow.setTextSize(TypedValue.COMPLEX_UNIT_PX, b[3] * factor)
        nextText.maxWidth = px(4)
        content.setPaddingRelative(px(5), px(6), px(5), px(6))
        content.minimumWidth = px(7)
        playNow.iconSize = px(8)
        playNow.iconPadding = px(9)
        playNow.minHeight = px(10)
        playNow.minimumHeight = px(10)
        cancel.minHeight = px(11)
        cancel.minimumHeight = px(11)
        playNow.setPaddingRelative(px(12), px(13), px(12), px(13))
        cancel.setPaddingRelative(px(14), px(15), px(14), px(15))
        playNow.insetTop = px(19)
        playNow.insetBottom = px(19)
        cancel.insetTop = px(20)
        cancel.insetBottom = px(20)
        (playNow.layoutParams as ViewGroup.MarginLayoutParams).marginStart = px(16)
        ((playNow.parent as View).layoutParams as ViewGroup.MarginLayoutParams).topMargin = px(17)
        card.radius = b[18] * factor
        size = factor
        content.requestLayout()
    }

    /** Hides it without running anything: a new episode, the player closing. Fades and shrinks out. */
    fun hide() {
        handler.removeCallbacks(tick)
        onPlay = null
        onCancel = null
        isCounting = false
        val wasActive = active
        active = false
        if (!wasActive || hiding) return
        if (!animationsEnabled || !card.isVisible) {
            finishHide()
            return
        }
        hiding = true
        card.animate().cancel()
        card.animate().alpha(0f)
            .scaleX(card.scaleX * APPEAR_SCALE).scaleY(card.scaleY * APPEAR_SCALE)
            .setDuration(180).setInterpolator(FastOutSlowInInterpolator())
            .withEndAction { if (hiding) finishHide() }
            .start()
    }

    /** Gone for real, and set back to the layout's own size and place for the next showing. */
    private fun finishHide() {
        hiding = false
        card.animate().cancel()
        card.isVisible = false
        card.alpha = 1f
        card.translationX = 0f
        card.translationY = 0f
        card.scaleX = 1f
        card.scaleY = 1f
        applySize(1f)
    }

    private fun fire() {
        val play = onPlay
        hide()
        play?.invoke()
    }

    companion object {
        const val SECONDS = 5
        private const val CENTERED_WIDTH = 0.4f
        private const val MAX_SCALE = 2f
        /** Screens with a shorter side from this up get the corner card at the layout's size. */
        private const val FULL_SIZE_SHORT_SIDE_DP = 560f
        private const val MIN_CORNER_SIZE = 0.7f
        /** How small it starts when it appears, and ends when it goes. */
        private const val APPEAR_SCALE = 0.92f
    }
}
