package ani.dantotsu.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.updateLayoutParams
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemSettingsSectionHeaderBinding
import ani.dantotsu.util.animationsEnabled
import com.google.android.material.color.MaterialColors
import android.view.animation.OvershootInterpolator

/**
 * Look and motion of a collapsible settings card, shared by [SettingsSectionAdapter]'s cards, the
 * [ani.dantotsu.others.Xpandable] groups on the Player and Reader screens, and the Accounts
 * screen's [AccountCardAdapter] cards.
 *
 * Closed, a card is a soft filled surface with its icon on a tinted tile. Open, the surface takes a
 * wash of the theme's primary colour and the tile fills solid, so the group being worked in stands
 * out from the ones around it. Expanding grows the card to its new height rather than snapping.
 */
object SectionCard {
    private const val DURATION = 280L

    /** The card's fill: surface with a hint of on-surface closed, a hint of primary open. */
    fun fill(view: View, open: Boolean): Int {
        val surface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface)
        val overlay = MaterialColors.getColor(
            view,
            if (open) androidx.appcompat.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOnSurface
        )
        return MaterialColors.layer(surface, overlay, if (open) 0.09f else 0.05f)
    }

    private class Palette(
        val tile: Int,
        val icon: Int,
        val chevronBack: Int,
        val chevron: Int,
    )

    private fun palette(view: View, open: Boolean): Palette {
        val primary = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimary)
        val onSurface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant =
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
        return if (open) Palette(
            tile = primary,
            icon = onPrimary,
            chevronBack = MaterialColors.compositeARGBWithAlpha(primary, 40),
            chevron = primary,
        ) else Palette(
            tile = MaterialColors.compositeARGBWithAlpha(primary, 36),
            icon = primary,
            chevronBack = MaterialColors.compositeARGBWithAlpha(onSurface, 18),
            chevron = onSurfaceVariant,
        )
    }

    /**
     * Styles [header] (and [setFill], the card's own fill) for [open], animating from the previous
     * state when [animate]. The chevron turns with a little overshoot.
     */
    fun bind(
        header: ItemSettingsSectionHeaderBinding,
        open: Boolean,
        animate: Boolean,
        setFill: (Int) -> Unit,
    ) = bind(header.root, header.sectionIconTile, header.sectionIcon, header.sectionChevron, open, animate, setFill)

    /**
     * [bind] for a header of its own (the Accounts screen's): [tile] and [icon] are optional, the
     * rest is the same.
     */
    fun bind(
        root: View,
        tile: View?,
        icon: ImageView?,
        chevron: ImageView,
        open: Boolean,
        animate: Boolean,
        setFill: (Int) -> Unit,
    ) {
        val to = palette(root, open)
        val toFill = fill(root, open)
        chevron.animate().cancel()
        (root.getTag(R.id.section_card_anim) as? Animator)?.cancel()

        if (!animate || !animationsEnabled) {
            apply(tile, icon, chevron, to)
            setFill(toFill)
            chevron.rotation = if (open) 180f else 0f
            return
        }
        val from = palette(root, !open)
        val fromFill = fill(root, !open)
        val argb = ArgbEvaluator()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                fun mix(a: Int, b: Int) = argb.evaluate(t, a, b) as Int
                apply(
                    tile, icon, chevron,
                    Palette(
                        mix(from.tile, to.tile), mix(from.icon, to.icon),
                        mix(from.chevronBack, to.chevronBack), mix(from.chevron, to.chevron),
                    )
                )
                setFill(mix(fromFill, toFill))
            }
            root.setTag(R.id.section_card_anim, this)
        }.start()
        chevron.animate().rotation(if (open) 180f else 0f).setDuration(DURATION + 80)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun apply(tile: View?, icon: ImageView?, chevron: ImageView, p: Palette) {
        tile?.backgroundTintList = ColorStateList.valueOf(p.tile)
        icon?.imageTintList = ColorStateList.valueOf(p.icon)
        chevron.backgroundTintList = ColorStateList.valueOf(p.chevronBack)
        chevron.imageTintList = ColorStateList.valueOf(p.chevron)
    }

    /**
     * Opens or closes a card's [body], which holds its [rows] list: grows or shrinks it to fit,
     * the rows fading in or out. [show] fills and shows the body (true) or hides it (false).
     */
    fun toggleBody(body: View, rows: RecyclerView, open: Boolean, show: (Boolean) -> Unit) {
        if (open) {
            val from = if (body.isVisible) body.height else 0
            show(true)
            if (!body.isVisible) return
            val to = naturalHeight(body)
            pinRows(rows, rows.measuredHeight)
            animateHeight(body, from, to) { pinRows(rows, null) }
            revealContents(body)
        } else {
            hideContents(body)
            pinRows(rows, rows.height)
            animateHeight(body, body.height, 0) {
                pinRows(rows, null)
                show(false)
            }
        }
    }

    /** Drops whatever [toggleBody] left mid-flight on a card being rebound. */
    fun resetBody(body: View, rows: RecyclerView) {
        body.animate().cancel()
        body.alpha = 1f
        body.translationY = 0f
        animateHeight(body, 0, 0)
        pinRows(rows, null)
    }

    /**
     * Holds [rows] at their full [height] while the card around them grows or shrinks, or lets them
     * size themselves again when null. Left to wrap, the list would be squeezed to the card's height
     * on every frame and recycle rows as it went, rebinding them mid-animation.
     */
    private fun pinRows(rows: RecyclerView, height: Int?) {
        rows.updateLayoutParams { this.height = height ?: ViewGroup.LayoutParams.WRAP_CONTENT }
    }

    /** The whole [card] gives a little under the finger while its [header] is pressed. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressFeedback(header: View, card: View) {
        header.setOnTouchListener { _, event ->
            if (!animationsEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Around the header, not the card's centre: the card changes height as the
                    // tap opens or closes it, and a moving pivot would drag the header along.
                    card.pivotX = card.width / 2f
                    card.pivotY = header.top + header.height / 2f
                    card.animate().scaleX(0.98f).scaleY(0.98f).setDuration(120)
                        .setInterpolator(FastOutSlowInInterpolator()).start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    card.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(OvershootInterpolator(2f)).start()
            }
            false
        }
    }

    /**
     * Shows or hides [view] by easing its height open or shut, its contents fading with it: the
     * motion of a card's body, for the collapsible panels that aren't cards (filter sections, list
     * rows with a detail panel). Opening a panel that's mid-close picks up from where it got to.
     * [onHidden] runs once a closing panel is gone.
     */
    fun setExpanded(view: View, open: Boolean, onHidden: () -> Unit = {}) {
        val closing = view.getTag(R.id.section_collapsing) == true
        if (!animationsEnabled || !view.isAttachedToWindow) {
            view.setTag(R.id.section_collapsing, null)
            (view.getTag(R.id.section_height_anim) as? Animator)?.cancel()
            view.animate().cancel()
            view.alpha = 1f
            view.translationY = 0f
            view.updateLayoutParams { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            view.isVisible = open
            if (!open) onHidden()
            return
        }
        if (open) {
            if (view.isVisible && !closing) return
            val from = if (view.isVisible) view.height else 0
            view.setTag(R.id.section_collapsing, null)
            view.animate().cancel()
            view.isVisible = true
            animateHeight(view, from, naturalHeight(view))
            revealContents(view)
        } else {
            if (!view.isVisible || closing) return
            view.setTag(R.id.section_collapsing, true)
            hideContents(view)
            animateHeight(view, view.height, 0) {
                view.setTag(R.id.section_collapsing, null)
                view.isVisible = false
                view.alpha = 1f
                onHidden()
            }
        }
    }

    /**
     * Drops whatever [setExpanded] left mid-flight on a panel whose row is being rebound, so a
     * recycled row never inherits another row's half-open height or fade.
     */
    fun resetPanel(view: View) {
        (view.getTag(R.id.section_height_anim) as? Animator)?.cancel()
        view.setTag(R.id.section_collapsing, null)
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
        view.updateLayoutParams { height = ViewGroup.LayoutParams.WRAP_CONTENT }
    }

    /**
     * Runs [change] — anything that alters [view]'s natural height, such as a text's line limit or
     * a chip row wrapping — and eases [view] from the height it had to the one it now wants, rather
     * than letting it jump and shove everything below it.
     */
    fun animateHeightChange(view: View, change: () -> Unit) {
        val from = view.height
        change()
        if (!animationsEnabled || !view.isLaidOut || from == 0) return
        animateHeight(view, from, naturalHeight(view))
    }

    /** [view]'s height laid out at its width (its parent's, if it has none yet) with no height limit. */
    fun naturalHeight(view: View): Int {
        // A hidden view has no width of its own; it will get its parent's, inside the parent's
        // padding and its own margins. Measured any wider, wrapped text comes out too short.
        val width = view.width.takeIf { it > 0 } ?: (view.parent as? View)?.let { parent ->
            val margins = (view.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.let { it.marginStart + it.marginEnd } ?: 0
            parent.width - parent.paddingLeft - parent.paddingRight - margins
        } ?: 0
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return view.measuredHeight
    }

    /**
     * Grows or shrinks [view] from [from] to [to] px, then hands its height back to [restore]
     * (its usual layout height) and runs [onEnd]. A newer call on the same view takes over from
     * wherever this one got to, without running this one's [onEnd].
     */
    fun animateHeight(
        view: View,
        from: Int,
        to: Int,
        restore: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        onEnd: () -> Unit = {},
    ) {
        (view.getTag(R.id.section_height_anim) as? Animator)?.cancel()
        if (!animationsEnabled || from == to) {
            view.updateLayoutParams { height = restore }
            onEnd()
            return
        }
        view.updateLayoutParams { height = from }
        ValueAnimator.ofInt(from, to).apply {
            duration = DURATION
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim -> view.updateLayoutParams { height = anim.animatedValue as Int } }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.setTag(R.id.section_height_anim, null)
                    if (cancelled) return
                    view.updateLayoutParams { height = restore }
                    onEnd()
                }
            })
            view.setTag(R.id.section_height_anim, this)
        }.start()
    }

    /** The open card's contents drifting down into place as they fade in. */
    fun revealContents(view: View) {
        if (!animationsEnabled) return
        view.alpha = 0f
        view.translationY = -12f * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(DURATION)
            .setInterpolator(FastOutSlowInInterpolator()).start()
    }

    /** The closing card's contents fading as it shrinks over them. */
    fun hideContents(view: View) {
        if (!animationsEnabled) return
        view.animate().alpha(0f).setStartDelay(0).setDuration(DURATION / 2).start()
    }
}
