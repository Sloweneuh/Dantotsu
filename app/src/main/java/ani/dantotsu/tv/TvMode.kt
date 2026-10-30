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

    fun isTvDevice(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION

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
        activity.startActivity(
            Intent(activity, TvHomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    /** Back to the phone interface, until switched on again (even on a TV). */
    fun exit(activity: Activity) {
        PrefManager.setVal(PrefName.BigPictureMode, OFF)
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    /** A connected gamepad or joystick: the moment to offer the TV interface. */
    fun hasController(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.isController() == true
    }

    fun InputDevice.isController(): Boolean =
        !isVirtual && (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
}
