package ani.dantotsu.tv

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.InputDevice
import ani.dantotsu.MainActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * A screen of the TV interface. Phone-only chrome — the top notice banners, say — stays off these,
 * since a remote can't reach it.
 */
interface TvScreen

/**
 * TV mode: a separate, anime-only, remote- and controller-driven UI. It
 * starts on its own on Android TV, and anywhere else when switched on ([PrefName.BigPictureMode]:
 * -1 automatic, 0 off, 1 on — kept per device).
 */
object TvMode {

    const val AUTOMATIC = -1
    const val OFF = 0
    const val ON = 1

    /**
     * What a TV screen does in attachBaseContext: dark (a bright screen glares across a dark room)
     * and in the app's language. Dark has to be set here, before AppCompat sets the screen up: it
     * otherwise fixes the screen to the app's own dark mode setting — the phone's, once cloud sync
     * brings it — and on starting finds that it no longer matches the screen's dark, so it rebuilds
     * the screen; the rebuilt one began the same way, and the TV home rebuilt itself every half
     * second, its loads with it.
     */
    fun attach(activity: androidx.appcompat.app.AppCompatActivity, base: Context?): Context? {
        activity.delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        return base?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) }
    }

    /**
     * Asked of the app's context, not the one given: an activity's base context, as it's being
     * attached, hands out a UiModeManager bound to the activity itself, which has no context of
     * its own yet; on Android 17 reading the mode then looks up its display and crashed the player
     * as it opened (it asks in attachBaseContext).
     */
    fun isTvDevice(context: Context): Boolean =
        ((context.applicationContext ?: context).getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    /**
     * Whether the app should open into TV mode on this device. Always, on a TV: the touch interface
     * is no use with a remote, and nothing in it would be reachable to come back.
     */
    fun isOn(context: Context): Boolean = isTvDevice(context) ||
        PrefManager.getVal<Int>(PrefName.BigPictureMode) == ON

    /** Whether TV mode can be left here — anywhere but a TV. */
    fun canExit(context: Context): Boolean = !isTvDevice(context)

    /** Switches to the TV interface, leaving nothing of the phone one behind to back out into. */
    fun enter(activity: Activity) {
        PrefManager.setVal(PrefName.BigPictureMode, ON)
        // The phone interface's window, as the user had it: leaving TV mode goes back to it ([exit]).
        if (ani.dantotsu.connections.handoff.HandoffManager.isWsa(activity)) windowBounds(activity)?.let {
            PrefManager.setCustomVal(PHONE_WINDOW, it.flattenToString())
        }
        startHome(activity, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    /**
     * Opens TV home, with [flags] as the caller wants them: every way into TV mode goes through
     * here. On WSA its window stays the size WSA remembers for the app — it applies that over any
     * size an app asks for — so full screen there is WSA's own F11, which TV home suggests.
     */
    fun startHome(context: Context, flags: Int = 0) {
        val intent = Intent(context, TvHomeActivity::class.java).addFlags(flags)
        // On WSA, from the phone interface (not a TV screen restarting TV mode): TV mode back at the
        // size it last had. A window keeps its size within its task, and TV mode opened in the phone
        // interface's, at its size; only a new window takes the one asked for (and not with the task
        // cleared), so it opens in its own and the phone interface's is closed.
        val bounds = if (context !is TvScreen && ani.dantotsu.connections.handoff.HandoffManager.isWsa(context)) {
            savedBounds(TV_WINDOW) ?: desktopBounds(context)
        } else null
        if (bounds == null) {
            context.startActivity(intent)
            return
        }
        intent.removeFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        context.startActivity(intent, android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle())
        (context as? Activity)?.finishAndRemoveTask()
    }

    /**
     * Notes the size TV mode's window has, for [startHome] to open it at next time: on leaving it,
     * and whenever TV home goes to the background (the app closed while in TV mode). WSA only.
     */
    fun rememberTvWindow(activity: Activity) {
        if (!ani.dantotsu.connections.handoff.HandoffManager.isWsa(activity)) return
        val now = windowBounds(activity) ?: return
        // Only a size the user gave it: one WSA imposed (the window it remembered for the app,
        // over the size asked for) was saved too, and asked for at every launch after, so TV mode
        // stayed that small until resized by hand.
        if (now == openedAt[activity]) return
        PrefManager.setCustomVal(TV_WINDOW, now.flattenToString())
    }

    /** The size each TV home window opened at ([tvWindowOpened]), to tell a resize from it. */
    private val openedAt = java.util.WeakHashMap<Activity, android.graphics.Rect>()

    /** Notes the size [activity]'s window opened at, the first time it's in front. WSA only. */
    fun tvWindowOpened(activity: Activity) {
        if (activity in openedAt || !ani.dantotsu.connections.handoff.HandoffManager.isWsa(activity)) return
        windowBounds(activity)?.let { openedAt[activity] = it }
    }

    /**
     * The screen less room for Windows' title bar above the window and its taskbar below: as large
     * as WSA lets a window open. Asked for the whole display (0, 0 to its full size), it opened the
     * window at its remembered size instead — a window there would have its title bar off the top
     * and its bottom under the taskbar.
     */
    private fun desktopBounds(context: Context): android.graphics.Rect? = runCatching {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        // The physical mode, not the display's metrics: asked from inside a window, WSA reports the
        // display as that window, turned on its side (543 by 1000 from a 1000 by 543 window), and
        // TV mode asked to be the phone window's size.
        val mode = display.mode
        val width = maxOf(mode.physicalWidth, mode.physicalHeight)
        val height = minOf(mode.physicalWidth, mode.physicalHeight)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") display.getRealMetrics(metrics)
        val scale = metrics.densityDpi / 160f
        android.graphics.Rect(0, (WSA_TITLE_BAR_DP * scale).toInt(), width, height - (WSA_TASKBAR_DP * scale).toInt())
    }.getOrNull()?.takeIf { !it.isEmpty }

    /** Windows' title bar over a WSA window, and the taskbar, in dp (at 160 dpi, WSA's pixels). */
    private const val WSA_TITLE_BAR_DP = 32
    private const val WSA_TASKBAR_DP = 48

    private fun savedBounds(key: String): android.graphics.Rect? =
        PrefManager.getNullableCustomVal(key, null, String::class.java)
            ?.let { android.graphics.Rect.unflattenFromString(it) }?.takeIf { !it.isEmpty }

    /**
     * Back to the phone interface. Automatic rather than off: leaving can just mean wanting to read
     * manga, which TV mode doesn't have, so a controller still offers TV mode on the next launch.
     * Not on this one, having only just left it.
     */
    fun exit(activity: Activity) {
        PrefManager.setVal(PrefName.BigPictureMode, AUTOMATIC)
        ControllerOfferNotice.skipThisLaunch()
        rememberTvWindow(activity)
        val intent = Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // On WSA, back in the window the phone interface had before TV mode: a window of its own,
        // as only a new one takes a size (and not with the task cleared, or WSA kept the old
        // window's), and TV mode's closed.
        val bounds = if (ani.dantotsu.connections.handoff.HandoffManager.isWsa(activity)) savedBounds(PHONE_WINDOW) else null
        if (bounds == null) {
            activity.startActivity(intent)
            return
        }
        intent.removeFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        activity.startActivity(intent, android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle())
        activity.finishAndRemoveTask()
    }

    /** Where [activity]'s window is on the screen now. */
    private fun windowBounds(activity: Activity): android.graphics.Rect? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.windowManager.currentWindowMetrics.bounds
        } else {
            val location = IntArray(2)
            val decor = activity.window.decorView
            decor.getLocationOnScreen(location)
            android.graphics.Rect(location[0], location[1], location[0] + decor.width, location[1] + decor.height)
        }
    }.getOrNull()?.takeIf { !it.isEmpty }

    /** The phone interface's window before TV mode ([enter], [exit]); on WSA only. */
    private const val PHONE_WINDOW = "tv_phone_window_bounds"

    /** TV mode's own window, the last time it was left ([rememberTvWindow], [startHome]); on WSA only. */
    private const val TV_WINDOW = "tv_window_bounds_3"

    /** A connected gamepad or joystick: the moment to offer the TV interface. */
    fun hasController(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.isController() == true
    }

    fun InputDevice.isController(): Boolean =
        // A keyboard can claim gamepad keys too (WSA's does): that doesn't make it a controller.
        !isVirtual && keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC && (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
}
