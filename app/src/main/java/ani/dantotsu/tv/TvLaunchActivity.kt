package ani.dantotsu.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import ani.dantotsu.MainActivity

/**
 * Where the TV's home screen opens the app. It draws nothing — its window is black, as every TV
 * screen's is (Theme.Dantotsu.Tv) — and hands straight over: to TV mode, or to the phone interface
 * where TV mode was switched off. Opening the main screen directly started on its window, which
 * follows the system's light theme on a TV that isn't set dark: a full white frame, in a dark room.
 */
class TvLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (TvMode.isOn(this)) {
            Intent(this, TvHomeActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
        }
        startActivity(target)
        finish()
        // Black to TV home's own dark: a fade, not the system's slide over the launcher.
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
