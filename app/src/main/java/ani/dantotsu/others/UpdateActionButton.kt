package ani.dantotsu.others

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import ani.dantotsu.px
import kotlin.math.PI
import kotlin.math.sin

/**
 * The update screen's single action control: a filled pill that doubles as the download progress
 * bar, so the button is never replaced by a separate widget.
 *
 * Progress is drawn as a lighter body of liquid rising over the solid button rather than a distinct
 * track, with a sine wave along its leading edge. Keeping it an overlay means the label holds full
 * contrast at any fill level, which a dimmed-track-plus-solid-fill arrangement can't promise across
 * themes.
 */
class UpdateActionButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val iconBase = ImageView(context)
    private val iconArrow = ImageView(context)
    private val iconBox = FrameLayout(context)
    private val label = TextView(context)
    private val percent = TextView(context)
    private val content = LinearLayout(context)

    private val pill = GradientDrawable()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePath = Path()

    private var fillFraction = 0f
    private var wavePhase = 0f

    private var fillAnimator: ValueAnimator? = null
    private var waveAnimator: ValueAnimator? = null
    private var arrowAnimator: ValueAnimator? = null

    private data class Content(val text: CharSequence, val base: Int, val arrow: Int)

    private var shown: Content? = null
    private var pending: Content? = null

    init {
        val primary = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val onPrimary = context.getThemeColor(com.google.android.material.R.attr.colorOnPrimary)

        pill.setColor(primary)
        background = pill
        fillPaint.color = ColorUtils.setAlphaComponent(onPrimary, 70)
        setWillNotDraw(false)

        // Ripple above the fill, clipped to the pill by the background's outline.
        TypedValue().let {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            if (it.resourceId != 0) foreground = ContextCompat.getDrawable(context, it.resourceId)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        isClickable = true
        isFocusable = true

        val bold = ResourcesCompat.getFont(context, R.font.poppins_bold)
        listOf(label, percent).forEach {
            it.setTextColor(onPrimary)
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            it.typeface = bold
            it.maxLines = 1
        }
        percent.visibility = GONE
        iconBase.imageTintList = label.textColors
        iconArrow.imageTintList = label.textColors

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        iconBox.addView(iconBase, LayoutParams(match, match))
        iconBox.addView(iconArrow, LayoutParams(match, match))

        content.orientation = LinearLayout.HORIZONTAL
        content.gravity = Gravity.CENTER
        content.addView(
            iconBox,
            LinearLayout.LayoutParams(20f.px, 20f.px).apply { marginEnd = 8f.px }
        )
        content.addView(label, LinearLayout.LayoutParams(wrap, wrap))
        content.addView(
            percent,
            LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = 6f.px }
        )
        addView(content, LayoutParams(wrap, wrap, Gravity.CENTER))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pill.cornerRadius = h / 2f
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fillFraction <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val amp = WAVE_AMPLITUDE_DP.px.toFloat()

        // Span the edge over an extra amplitude at each end so 0% and 100% are clean, not notched.
        val edge = fillFraction * (w + amp * 2f) - amp
        wavePath.reset()
        wavePath.moveTo(0f, 0f)
        for (i in 0..WAVE_STEPS) {
            val y = h * i / WAVE_STEPS
            val phase = wavePhase + (y / h) * WAVE_CYCLES * 2f * PI.toFloat()
            wavePath.lineTo(edge + amp * sin(phase), y)
        }
        wavePath.lineTo(0f, h)
        wavePath.close()
        canvas.drawPath(wavePath, fillPaint)
    }

    /**
     * Swaps the label and icon together with a vertical slide. The percentage beside them is left
     * alone — it updates in place, since sliding it on every progress tick would strobe.
     */
    fun setContent(text: CharSequence, @DrawableRes base: Int, @DrawableRes arrow: Int = 0) {
        val next = Content(text, base, arrow)
        contentDescription = text
        // pending matters because the owner re-renders on every progress tick: without it a repeat
        // of the same target would restart the slide it is already halfway through.
        if (shown == next || pending == next) return
        if (!isLaidOut || shown == null) {
            apply(next)
            return
        }
        val travel = 10f.px.toFloat()
        pending = next
        content.animate().cancel()
        content.animate()
            .translationY(-travel).alpha(0f).setDuration(110)
            .withEndAction {
                apply(next)
                content.translationY = travel
                content.alpha = 0f
                content.animate().translationY(0f).alpha(1f).setDuration(170).start()
            }
            .start()
    }

    private fun apply(c: Content) {
        shown = c
        pending = null
        label.text = c.text
        iconBase.setImageResource(c.base)
        iconBase.imageTintList = label.textColors
        if (c.arrow != 0) {
            iconArrow.setImageResource(c.arrow)
            iconArrow.imageTintList = label.textColors
            iconArrow.visibility = VISIBLE
        } else {
            iconArrow.visibility = GONE
        }
    }

    fun setPercentText(text: CharSequence?) {
        percent.text = text ?: ""
        percent.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    /** Fills to [value] percent, easing there so stepped progress reports still read as a flow. */
    fun setProgress(value: Int, animate: Boolean = true) {
        val target = value.coerceIn(0, 100) / 100f
        fillAnimator?.cancel()
        if (!animate) {
            fillFraction = target
            invalidate()
            return
        }
        fillAnimator = ValueAnimator.ofFloat(fillFraction, target).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fillFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Runs the wave and sinks the arrow into the tray on a loop for as long as it's downloading. */
    fun setDownloading(downloading: Boolean) {
        if (!downloading) {
            waveAnimator?.cancel()
            waveAnimator = null
            arrowAnimator?.cancel()
            arrowAnimator = null
            iconArrow.translationY = 0f
            iconArrow.alpha = 1f
            return
        }
        if (waveAnimator?.isRunning != true) {
            waveAnimator = ValueAnimator.ofFloat(0f, 2f * PI.toFloat()).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    wavePhase = it.animatedValue as Float
                    if (fillFraction > 0f) invalidate()
                }
                start()
            }
        }
        if (arrowAnimator?.isRunning != true) {
            val from = -2f.px.toFloat()
            val to = 5f.px.toFloat()
            arrowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 850
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val f = it.animatedFraction
                    iconArrow.translationY = from + (to - from) * f
                    iconArrow.alpha = when {
                        f < 0.2f -> f / 0.2f
                        f > 0.65f -> (1f - (f - 0.65f) / 0.35f).coerceAtLeast(0f)
                        else -> 1f
                    }
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        setDownloading(false)
        fillAnimator?.cancel()
        fillAnimator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val WAVE_AMPLITUDE_DP = 3f
        const val WAVE_CYCLES = 1.5f
        const val WAVE_STEPS = 18
    }
}
