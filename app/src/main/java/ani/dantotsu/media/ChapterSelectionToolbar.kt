package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.cardview.widget.CardView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.Fragment
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentMediaSourceBinding
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.util.animationsEnabled
import ani.dantotsu.util.springSlideVisible
import com.google.android.material.color.MaterialColors

/**
 * The floating toolbar shown while chapters or episodes are long-press selected on a media
 * page's Read / Watch tab (manga, anime and novel share it; its views live in
 * fragment_media_source). Each tab supplies the actions; this handles showing, placing and
 * hiding it, the count, and Back clearing the selection.
 *
 * While shown, the toolbar is moved into the activity's content view and sat 16dp above the
 * page's tab bar: the tab itself can end well above the bar, and inside it the toolbar couldn't
 * get any lower without being clipped. Call [detach] from onDestroyView.
 */
class ChapterSelectionToolbar(
    private val fragment: Fragment,
    private val binding: FragmentMediaSourceBinding,
    onMarkRead: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    private val onClear: () -> Unit,
) {
    private val toolbar = binding.chapterSelectionToolbar
    private val backClears = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = onClear()
    }

    /** True while anything is selected. */
    var isActive = false
        private set

    init {
        fragment.requireActivity().onBackPressedDispatcher
            .addCallback(fragment.viewLifecycleOwner, backClears)
        binding.chapterSelectionRead.setOnClickListener { onMarkRead() }
        binding.chapterSelectionDownload.setOnClickListener { onDownload() }
        binding.chapterSelectionDelete.setOnClickListener { onDelete() }
        binding.chapterSelectionAll.setOnClickListener { onSelectAll() }
        binding.chapterSelectionClose.setOnClickListener { onClear() }
    }

    /** Call whenever the selection changes. [canDelete]: something selected has a download. */
    fun update(count: Int, canDelete: Boolean) {
        isActive = count > 0
        binding.chapterSelectionCount.text = count.toString()
        binding.chapterSelectionDelete.isVisible = canDelete
        backClears.isEnabled = isActive
        if (isActive) {
            binding.ScrollTop.visibility = View.GONE
            place()
        }
        toolbar.springSlideVisible(isActive)
    }

    fun detach() {
        (toolbar.parent as? ViewGroup)?.removeView(toolbar)
    }

    private fun place() {
        val activity = fragment.activity ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (toolbar.parent !== content) {
            (toolbar.parent as? ViewGroup)?.removeView(toolbar)
            content.addView(
                toolbar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                )
            )
        }
        val bar = activity.findViewById<View>(R.id.mediaBottomBar)
            ?.takeIf { it.isShown && it.width > it.height }
        val contentBottom = IntArray(2).also { content.getLocationInWindow(it) }[1] + content.height
        val above = bar?.let { contentBottom - IntArray(2).also { xy -> it.getLocationInWindow(xy) }[1] }
            ?: navBarHeight
        toolbar.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = above + 16f.px }
    }
}

/**
 * Tints a chapter/episode row while it's selected. [animate] (a toggle, not a rebind) fades the
 * tint and gives the row a small spring bounce, so the long-press visibly lands. The tint is
 * rounded to the row's card so it doesn't poke out at the corners.
 */
fun View.bindSelectionTint(selected: Boolean, animate: Boolean) {
    val tint = foreground as? GradientDrawable
        ?: GradientDrawable().apply {
            setColor(MaterialColors.getColor(this@bindSelectionTint, androidx.appcompat.R.attr.colorPrimary))
            cornerRadius = (this@bindSelectionTint as? CardView)?.radius ?: 0f
            alpha = 0
            foreground = this
        }
    val target = if (selected) 0x40 else 0
    if (!animate || !animationsEnabled) {
        tint.alpha = target
        return
    }
    ObjectAnimator.ofInt(tint, "alpha", tint.alpha, target).setDuration(200).start()
    scaleX = 0.94f
    scaleY = 0.94f
    springTo(1f, SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY, SpringForce.STIFFNESS_MEDIUM)
}

/** A small spring bounce, for an icon whose state just changed (a download finished). */
fun View.popIn() {
    if (!animationsEnabled) return
    scaleX = 0.5f
    scaleY = 0.5f
    springTo(1f, SpringForce.DAMPING_RATIO_HIGH_BOUNCY, SpringForce.STIFFNESS_LOW)
}

private fun View.springTo(scale: Float, damping: Float, stiffness: Float) {
    listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y).forEach { property ->
        SpringAnimation(this, property, scale).apply {
            spring.dampingRatio = damping
            spring.stiffness = stiffness
        }.start()
    }
}
