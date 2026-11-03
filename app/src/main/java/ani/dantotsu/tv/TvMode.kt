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
        // The phone interface's window, as the user had it: TV mode takes the whole screen on WSA,
        // and leaving it goes back to this ([exit]).
        if (ani.dantotsu.connections.handoff.HandoffManager.isWsa(activity)) windowBounds(activity)?.let {
            PrefManager.setCustomVal(PHONE_WINDOW, it.flattenToString())
        }
        startHome(activity, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    /**
     * Opens TV home, with [flags] as the caller wants them. On WSA, in a window the size of the
     * screen: an app can't make its own window fullscreen there (Android 13 has no call for it), but
     * a window it opens can be given any size. Only a new window takes that size, so TV home opens
     * in one of its own, and the window it was opened from is closed — what clearing the task
     * did, the phone interface or the TV mode being reopened left behind.
     */
    fun startHome(context: Context, flags: Int = 0) {
        val intent = Intent(context, TvHomeActivity::class.java).addFlags(flags)
        val bounds = if (ani.dantotsu.connections.handoff.HandoffManager.isWsa(context)) screenBounds(context) else null
        if (bounds == null) {
            context.startActivity(intent)
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        context.startActivity(intent, android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle())
        (context as? Activity)?.finishAndRemoveTask()
    }

    /**
     * The whole screen: on WSA, the Windows desktop. The display's own size — not the window
     * manager's "maximum window", which for a window in a desktop-style mode is that window's own
     * bounds, so TV mode reopened exactly as small as the phone window it came from.
     */
    private fun screenBounds(context: Context): android.graphics.Rect? = runCatching {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") display.getRealMetrics(metrics)
        android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }.getOrNull()?.takeIf { !it.isEmpty }

    /** Back to the phone interface, until switched on again (even on a TV). */
    fun exit(activity: Activity) {
        PrefManager.setVal(PrefName.BigPictureMode, OFF)
        val intent = Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // On WSA, back in the window the phone interface had before TV mode took the screen: a
        // window of its own, as only a new one takes a size, and TV mode's closed.
        val bounds = if (ani.dantotsu.connections.handoff.HandoffManager.isWsa(activity)) {
            PrefManager.getNullableCustomVal(PHONE_WINDOW, null, String::class.java)
                ?.let { android.graphics.Rect.unflattenFromString(it) }?.takeIf { !it.isEmpty }
        } else null
        if (bounds == null) {
            activity.startActivity(intent)
            return
        }
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

    /** A connected gamepad or joystick: the moment to offer the TV interface. */
    fun hasController(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.isController() == true
    }

    fun InputDevice.isController(): Boolean =
        !isVirtual && (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
}
