package ani.dantotsu.tv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * The phone interface is for touch: a controller, a TV remote and a keyboard do nothing in it,
 * dialogs and sheets included. A D-pad or the arrow keys moved focus around it in ways nothing
 * there expects — a tab bar highlighted, a list scrolled under a finger, a dialog opened by a stray
 * press. Those are TV mode's.
 *
 * Left alone: typing into a text field (the keyboard's whole point there), media keys, volume and
 * power, a phone's own back key, a mouse, and keys injected without a device (testing). And one
 * thing is left to a controller or a remote: taking up the offer to switch to TV mode, which the
 * phone interface makes when a controller connects ([TvOfferHost]) — or there'd be no way into TV
 * mode from one.
 *
 * Told apart by where the input comes from, not by which key it is.
 */
object PhoneControllerBlock : Application.ActivityLifecycleCallbacks {

    /** The phone interface's offer to switch to TV mode, accepted with a controller's A or a remote's OK. */
    interface TvOfferHost {
        /** True when the offer was showing, and has been taken up. */
        fun acceptTvOffer(): Boolean
    }

    /**
     * Applies the block to [window] — an activity's, or a dialog's. Every window needs its own: a
     * dialog's keys never pass through its activity. [context] says whether TV mode is on.
     */
    fun guard(window: Window?, context: Context) {
        window ?: return
        val base = window.callback ?: return
        if (base is Filter) return
        val filter = Filter(window, context, base)
        window.callback = filter
        // A window already on screen (a dialog shown before it was guarded) gets its focus fix now.
        window.peekDecorView()?.takeIf { it.isAttachedToWindow }?.let { filter.watchTouchMode() }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // The callback only: the decor isn't touched here — making it this early would stop the
        // activity requesting window features afterwards (a crash, see ListActivity).
        guard(activity.window, activity)
        // A dialog fragment's window, as it shows.
        (activity as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                    (f as? DialogFragment)?.dialog?.let { guard(it.window, activity) }
                }
            },
            true,
        )
    }

    private class Filter(
        private val window: Window,
        private val context: Context,
        private val base: Window.Callback,
    ) : Window.Callback by base {
        private var watching = false

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (!blocked(event, window, context)) return base.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_UP && event.keyCode in ACCEPT_KEYS && fromController(event)) {
                (context as? TvOfferHost)?.acceptTvOffer()
            }
            return true
        }

        override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean =
            if (blocked(event, window, context)) true else base.dispatchKeyShortcutEvent(event)

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (TvMode.isOn(context)) return base.dispatchGenericMotionEvent(event)
            // A stick or a trigger: the D-pad's motion side, and the same story.
            if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return true
            return base.dispatchGenericMotionEvent(event)
        }

        override fun onAttachedToWindow() {
            base.onAttachedToWindow()
            watchTouchMode()
        }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            base.onWindowFocusChanged(hasFocus)
            // A window can open outside touch mode — the app started on WSA, last used by mouse or
            // keyboard — and Android then gives its first item focus by itself, with no key pressed
            // and no touch mode left to tell of it.
            if (hasFocus) window.peekDecorView()?.post { unfocus() }
        }

        /**
         * The first D-pad (or arrow) press on a screen never reaches the app: Android takes it to
         * leave touch mode, and gives the screen's first item focus — highlighted, the one press
         * that did something.
         */
        fun watchTouchMode() {
            if (watching) return
            val decor = window.peekDecorView() ?: return
            watching = true
            // The window itself never shows focus: Android's default highlight is a grey wash over it all.
            decor.defaultFocusHighlightEnabled = false
            decor.viewTreeObserver.addOnTouchModeChangeListener(ViewTreeObserver.OnTouchModeChangeListener { inTouchMode ->
                // After Android has placed that focus, which it does once this listener has run.
                if (!inTouchMode) decor.post { unfocus() }
            })
        }

        /**
         * Focus outside touch mode, given to the window itself, which shows none: an item holding
         * it is drawn highlighted. Not when a text field has it (a keyboard is typing there).
         */
        private fun unfocus() {
            if (TvMode.isOn(context)) return
            val group = window.peekDecorView() as? ViewGroup ?: return
            if (group.isInTouchMode) return
            val focused = window.currentFocus
            if (focused === group || focused?.onCheckIsTextEditor() == true) return
            val before = group.descendantFocusability
            group.isFocusable = true
            // Or Android draws its default focus highlight, a grey wash, over the whole window.
            group.defaultFocusHighlightEnabled = false
            group.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            group.requestFocus()
            group.descendantFocusability = before
        }
    }

    /** Whether [event] is one the phone interface ignores; see the class comment. */
    private fun blocked(event: KeyEvent, window: Window, context: Context): Boolean {
        if (TvMode.isOn(context)) return false
        if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
        if (event.keyCode in PASS_KEYS) return false
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        if (fromController(event)) return true
        val device = event.device ?: return false
        // A keyboard: only to type with, into a field that takes text.
        if (device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return window.currentFocus?.onCheckIsTextEditor() != true
        }
        // What's left is the phone's own keys (its back key, say).
        return false
    }

    /**
     * A gamepad's button, or a remote's: from a gamepad or a joystick, or a D-pad device without a
     * full keyboard (a remote).
     */
    private fun fromController(event: KeyEvent): Boolean {
        if (KeyEvent.isGamepadButton(event.keyCode)) return true
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            // A keyboard can claim gamepad keys too (WSA's does): its own keyboard keys still aren't one.
            if (event.device?.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true
        }
        val device = event.device ?: return false
        return event.isFromSource(InputDevice.SOURCE_DPAD) && device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    /** Never ours to take away: the system's keys, and media keys (the system's to route anyway). */
    private val PASS_KEYS = setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MUTE,
    )

    /** What accepts the TV mode offer: a controller's A or Start, a remote's OK. */
    private val ACCEPT_KEYS = setOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    )

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
