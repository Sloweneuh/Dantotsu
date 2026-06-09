package ani.dantotsu.connections.handoff

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import ani.dantotsu.databinding.ViewHandoffBannerBinding
import ani.dantotsu.loadImage
import ani.dantotsu.statusBarHeight
import kotlin.math.abs
import kotlin.math.min

/**
 * Self-dismissing "incoming handoff" banner, à la a game party invitation: it slides down from the
 * top of the current activity, shows the sender / cover / title (room for long, wrapping titles)
 * and what's resuming, then auto-dismisses after a few seconds.
 *
 * Unlike a dialog it doesn't block the rest of the screen. Tap it to open the media; flick or drag
 * it up to dismiss early. Only one banner is shown at a time — a newer handoff replaces the old.
 */
object HandoffIncomingBanner {

    private const val TAG = "handoff_incoming_banner"
    private const val AUTO_DISMISS_MS = 8_000L
    private const val ANIM_MS = 280L
    private val main = Handler(Looper.getMainLooper())

    private fun Int.px(activity: Activity) =
        (this * activity.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show(activity: Activity, payload: HandoffPayload, onOpen: () -> Unit) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // A newer handoff replaces any banner still on screen (and its pending auto-dismiss).
        main.removeCallbacksAndMessages(null)
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }

        val binding = ViewHandoffBannerBinding.inflate(LayoutInflater.from(activity))
        val card = binding.root.apply { tag = TAG }
        binding.handoffBannerFrom.text =
            activity.getString(ani.dantotsu.R.string.handoff_incoming_from, payload.senderName)
        binding.handoffBannerTitle.text = payload.title
        binding.handoffBannerCover.loadImage(payload.cover)
        val info = progressLine(activity, payload)
        binding.handoffBannerInfo.isVisible = info != null
        binding.handoffBannerInfo.text = info

        val side = 12.px(activity)
        card.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply { setMargins(side, statusBarHeight + side, side, 0) }
        content.addView(card)

        var dismissed = false
        val autoDismiss = Runnable { animateOut(content, card) { } }
        fun cancelAuto() = main.removeCallbacks(autoDismiss)
        fun scheduleAuto() = main.postDelayed(autoDismiss, AUTO_DISMISS_MS)

        // Slide in from above once we know the laid-out height.
        card.alpha = 0f
        card.post {
            card.translationY = -(card.height + side).toFloat()
            card.animate().translationY(0f).alpha(1f).setDuration(ANIM_MS).start()
        }
        scheduleAuto()

        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        card.setOnTouchListener(object : View.OnTouchListener {
            private var downY = 0f
            private var startY = 0f
            private var dragging = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY
                        startY = v.translationY
                        dragging = false
                        cancelAuto()
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - downY
                        if (!dragging && abs(dy) > slop) dragging = true
                        if (dragging) {
                            // Follow the finger upward only; fade as it leaves.
                            v.translationY = startY + min(0f, dy)
                            v.alpha = (1f + v.translationY / v.height).coerceIn(0f, 1f)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (dismissed) return true
                        if (!dragging) {
                            dismissed = true
                            cancelAuto()
                            animateOut(content, v) { onOpen() }
                        } else if (v.translationY < -v.height * 0.3f) {
                            dismissed = true
                            animateOut(content, v) { }
                        } else {
                            v.animate().translationY(0f).alpha(1f).setDuration(ANIM_MS).start()
                            scheduleAuto()
                        }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        v.animate().translationY(0f).alpha(1f).setDuration(ANIM_MS).start()
                        scheduleAuto()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun animateOut(content: ViewGroup, card: View, onEnd: () -> Unit) {
        main.removeCallbacksAndMessages(null)
        card.animate()
            .translationY(-(card.height + card.marginTopOrZero()).toFloat())
            .alpha(0f)
            .setDuration(ANIM_MS)
            .withEndAction {
                content.removeView(card)
                onEnd()
            }
            .start()
    }

    private fun View.marginTopOrZero(): Int =
        (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0

    /**
     * "Vol. 4 Ch. 20 • Page 8" / "Episode 5 • 12:34", or null for a media-only handoff.
     * The number is shown verbatim — the source already labels it (e.g. "Ch. 20"), so we don't
     * prefix our own "Chapter"/"Episode" and double it up.
     */
    private fun progressLine(activity: Activity, payload: HandoffPayload): String? {
        val number = payload.number?.takeIf { payload.hasProgress } ?: return null
        return if (payload.isAnime) {
            payload.positionMs?.takeIf { it > 0 }?.let { "$number • ${formatTime(it)}" } ?: number
        } else {
            payload.page?.takeIf { it > 0 }?.let {
                "$number • ${activity.getString(ani.dantotsu.R.string.handoff_page_label, it.toString())}"
            } ?: number
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }
}
