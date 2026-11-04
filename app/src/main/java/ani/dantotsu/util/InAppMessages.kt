package ani.dantotsu.util

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.copyToClipboard
import ani.dantotsu.dp
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.toast
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Every message the app shows, one at a time, as a snackbar on whichever screen is in front. The
 * system's toasts are never used: on Windows Subsystem for Android they turn into notifications.
 *
 * A snackbar belongs to one activity, so a message has to outlive the activity it was sent from.
 * One sent while no activity can show it waits for the next to resume, for as long as
 * [HELD_FOR_MILLIS]; that includes the app being in the background. One on screen when its activity
 * pauses goes back to the front of the line with the time it had left, so it carries on on the next
 * screen. Waiting messages cut the one on screen short, but not before [MIN_SHOWN_MILLIS].
 *
 * Each is drawn in a small window of its own, which stacks above any dialog or sheet already open
 * and takes neither focus nor the touches around it. Should the window be refused, the activity's
 * own content is used instead.
 */
object InAppMessages {
    private const val HELD_FOR_MILLIS = 5 * 60_000L
    private const val MIN_SHOWN_MILLIS = 1_200L
    private const val MIN_LEFT_TO_RESUME_MILLIS = 500L
    private const val MAX_WAITING = 5
    private const val SHORT_MIN_MILLIS = 2_000
    private const val LONG_MIN_MILLIS = 3_500
    private const val MAX_MILLIS = 4_000

    private class Message(
        val text: String,
        val clipboard: String?,
        val durationMillis: Int,
        val postedAt: Long,
        @StringRes val actionRes: Int?,
        val onAction: (() -> Unit)?,
    )

    private class Showing(
        val message: Message,
        val bar: Snackbar,
        val shownAt: Long,
        val window: MessageWindow?,
    ) {
        fun left(now: Long) = message.durationMillis - (now - shownAt)
    }

    private class MessageWindow(val activity: Activity, val host: ViewGroup) {
        fun remove() {
            runCatching { activity.windowManager.removeViewImmediate(host) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val waiting = ArrayDeque<Message>()
    private var resumed: Activity? = null
    private var showing: Showing? = null
    private val cutShort = Runnable { showing?.bar?.dismiss() }

    /**
     * From any thread. A message already showing or waiting isn't repeated, unless it has an action:
     * two "Removed" messages with an undo each undo something different.
     */
    fun post(
        text: String,
        clipboard: String? = null,
        long: Boolean = false,
        @StringRes actionRes: Int? = null,
        onAction: (() -> Unit)? = null,
    ) {
        val now = SystemClock.uptimeMillis()
        main.post {
            if (onAction == null && (showing?.message?.text == text || waiting.any { it.text == text })) {
                return@post
            }
            waiting.addLast(Message(text, clipboard, durationFor(text, long), now, actionRes, onAction))
            while (waiting.size > MAX_WAITING) waiting.removeFirst()
            advance()
        }
    }

    fun onResumed(activity: Activity) {
        resumed = activity
        advance()
    }

    fun onPaused(activity: Activity) {
        if (resumed !== activity) return
        resumed = null
        val current = showing ?: return
        showing = null
        main.removeCallbacks(cutShort)
        putBack(current)
        // Before the activity can go: a window still attached to it would be leaked.
        current.window?.remove()
        current.bar.dismiss()
    }

    private fun durationFor(text: String, long: Boolean): Int {
        val forLength = (1_500 + text.length * 30).coerceAtMost(MAX_MILLIS)
        return forLength.coerceAtLeast(if (long) LONG_MIN_MILLIS else SHORT_MIN_MILLIS)
    }

    private fun putBack(current: Showing) {
        val left = current.left(SystemClock.uptimeMillis())
        if (left < MIN_LEFT_TO_RESUME_MILLIS) return
        val message = current.message
        waiting.addFirst(
            Message(
                message.text, message.clipboard, left.toInt(), message.postedAt,
                message.actionRes, message.onAction,
            )
        )
    }

    private fun advance() {
        main.removeCallbacks(cutShort)
        val current = showing
        if (current != null) {
            if (waiting.isNotEmpty()) {
                val shownFor = SystemClock.uptimeMillis() - current.shownAt
                main.postDelayed(cutShort, (MIN_SHOWN_MILLIS - shownFor).coerceAtLeast(0))
            }
            return
        }
        val activity = resumed?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return
        val now = SystemClock.uptimeMillis()
        waiting.removeAll { now - it.postedAt > HELD_FOR_MILLIS }
        val next = waiting.firstOrNull() ?: return
        val window = openWindow(activity)
        // A screen that can't draw it keeps it waiting for the next one.
        val bar = build(activity, next, window)
        if (bar == null) {
            window?.remove()
            return
        }
        waiting.removeFirst()
        val shown = Showing(next, bar, now, window)
        showing = shown
        bar.addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (showing !== shown) return
                showing = null
                window?.remove()
                advance()
            }
        })
        bar.show()
        advance()
    }

    private fun openWindow(activity: Activity): MessageWindow? {
        val host = FrameLayout(activity)
        val params = WindowManager.LayoutParams(
            WRAP_CONTENT,
            WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = navBarHeight + 32f.px
            title = "InAppMessage"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        return try {
            activity.windowManager.addView(host, params)
            MessageWindow(activity, host)
        } catch (e: Exception) {
            Logger.log(e)
            null
        }
    }

    private fun build(activity: Activity, message: Message, window: MessageWindow?): Snackbar? = try {
        val target: View = window?.host ?: activity.findViewById(android.R.id.content)
        val bar = try {
            Snackbar.make(target, message.text, message.durationMillis)
        } catch (_: Exception) {
            // A screen without a Material theme, such as a translucent pass-through one.
            Snackbar.make(
                ContextThemeWrapper(activity, R.style.Theme_Dantotsu),
                target, message.text, message.durationMillis,
            )
        }
        if (message.actionRes != null && message.onAction != null) {
            bar.setAction(message.actionRes) { message.onAction.invoke() }
        }
        bar.view.apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                width = WRAP_CONTENT
            }
            // Its own window is already placed clear of the navigation bar.
            if (window == null) {
                translationY = -(navBarHeight.dp + 32f)
                translationZ = 32f
            }
            updatePadding(16f.px, right = 16f.px)
            // Past the default of two lines: some of these explain something.
            findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.maxLines = 4
            setOnClickListener { bar.dismiss() }
            setOnLongClickListener {
                copyToClipboard(message.clipboard ?: message.text, false)
                toast(context.getString(R.string.copied_to_clipboard))
                true
            }
        }
        bar
    } catch (e: Exception) {
        Logger.log(e)
        Injekt.get<CrashlyticsInterface>().logException(e)
        null
    }
}
