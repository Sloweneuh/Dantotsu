package ani.dantotsu.settings

import java.util.WeakHashMap
import ani.dantotsu.media.blendTint
import ani.dantotsu.media.SharedIconTint
import android.widget.ImageView
import android.view.View
import android.content.res.ColorStateList
import android.app.SharedElementCallback
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.app.ActivityOptionsCompat
import ani.dantotsu.databinding.ItemSettingsBinding
import android.os.Bundle
import android.transition.Transition
import ani.dantotsu.R
import ani.dantotsu.media.useCoverTransition
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.animationsEnabled
import com.google.android.material.transition.platform.MaterialSharedAxis

/**
 * Moving between settings pages on Material's shared X axis: a page opened drifts in from the right
 * as the one left drifts away and fades, and going back reverses it. The system's own activity
 * animation made every page change look like opening a different app screen; this reads as going
 * one level deeper into the same place, and back out of it.
 *
 * Two routes, depending on how a page is opened:
 *  - from a settings row — on the main page or one level down — with that row's icon carried into
 *    the page's header icon the way a media cover flies into its page ([open]). That is a scene
 *    transition, so the shared axis runs as the windows' content transitions, set up here;
 *  - from anywhere else, as a plain launch. The window animation style set here covers that, and
 *    a quick tile's grow-from-tile launch still wins over it, since launch options take precedence.
 *
 * The first version asked for the opening animation from the page's own onCreate —
 * overridePendingTransition before Android 14, overrideActivityTransition from 14 on — and it
 * didn't show: the former is only dependable from the activity doing the starting, right after it
 * starts. A window animation style is read by the system on every version, opening and closing.
 *
 * Installed once for every activity and applied only to the settings pages themselves — not to the
 * extension and media pages that happen to live in the settings package, which have their own
 * cover transitions. A page restarting itself to apply a setting ([beforeReload]) keeps its
 * cross-fade.
 */
object SettingsPageTransitions : Application.ActivityLifecycleCallbacks {
    /** Shared by a main-page row's icon and the header icon of every page, which is the target. */
    const val ICON_TRANSITION = "settingsPageIcon"

    private val pages: Set<Class<*>> = setOf(
        SettingsActivity::class.java,
        SettingsGeneralActivity::class.java,
        SettingsAppearanceActivity::class.java,
        SettingsAccountActivity::class.java,
        SettingsNotificationActivity::class.java,
        SettingsBackupSyncActivity::class.java,
        SettingsListSyncActivity::class.java,
        SettingsSourcesActivity::class.java,
        SettingsAboutActivity::class.java,
        AnilistSettingsActivity::class.java,
        PlayerSettingsActivity::class.java,
        ReaderSettingsActivity::class.java,
        ExtensionsActivity::class.java,
        FAQActivity::class.java,
        ListSyncCompareActivity::class.java,
        MUCustomListMappingActivity::class.java,
        ComickUnmatchedActivity::class.java,
        DesignLabActivity::class.java,
    )

    /** Per page, the row icon last given [ICON_TRANSITION]; see [open]. */
    private val namedIcons = WeakHashMap<Activity, View>()

    /**
     * Opens [intent] (a settings page) from [from] with [icon] — the tapped row's — carried into the
     * page's header icon, the way a media cover flies into its page, while the rest moves on the
     * shared axis. Pages one level down use it too, so going deeper reads the same at every level.
     * Only the tapped row's icon holds the transition name at a time: two views sharing it would
     * leave the return flight free to land on the wrong row. Falls back to a plain launch (the
     * window style's slide) with animations off or no icon on screen.
     */
    fun open(from: Activity, icon: View?, intent: Intent) {
        if (!animationsEnabled || icon == null || !icon.isAttachedToWindow) {
            from.startActivity(intent)
            return
        }
        namedIcons[from]?.transitionName = null
        icon.transitionName = ICON_TRANSITION
        namedIcons[from] = icon
        // The page's header icon blends from this row's tint as it lands; see SharedIconTint.
        SharedIconTint.forward = (icon as? ImageView)?.imageTintList?.defaultColor
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(from, icon, ICON_TRANSITION)
        from.startActivity(intent, options.toBundle())
    }

    /** [open] for a settings row: its icon flies into [page]'s header. */
    fun open(from: Activity, row: ItemSettingsBinding, page: Class<*>) =
        open(from, row.settingsIcon, Intent(from, page))

    /** Set while a page restarts itself: its reopening doesn't slide. */
    @Volatile
    private var reloading = false

    /**
     * Call before an activity finishes to restart itself, so the restart keeps its cross-fade
     * instead of playing as leaving one page and arriving at another.
     */
    fun beforeReload(activity: Activity) {
        if (activity.javaClass !in pages) return
        reloading = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.clearOverrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE)
        }
        activity.window.setWindowAnimations(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity.javaClass !in pages) return
        val restarted = reloading
        reloading = false
        if (!animationsEnabled || restarted) return
        val window = activity.window

        // Plain launches, opening and closing.
        window.setWindowAnimations(R.style.SettingsPageWindowAnimation)
        // Android 14+ runs back gestures through predictive back, which takes its closing motion
        // from here rather than from the window style.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.settings_page_pop_enter, R.anim.settings_page_pop_exit,
            )
        }

        // Scene-transition launches (a main-page row, its icon flying across): the same axis as
        // content transitions, and the icon on the covers' arc.
        window.enterTransition = sharedAxis(forward = true)
        window.exitTransition = sharedAxis(forward = true)
        window.returnTransition = sharedAxis(forward = false)
        window.reenterTransition = sharedAxis(forward = false)
        window.allowEnterTransitionOverlap = true
        window.allowReturnTransitionOverlap = true
        activity.useCoverTransition()
        activity.setEnterSharedElementCallback(HeaderIconTint(activity))
    }

    /**
     * The header icon's colour through both flights, one per page.
     *
     * Arriving from a main-page row, the icon starts in that row's tint and blends into its own
     * over the flight, rather than switching colour the instant it sets off. Going back, it blends
     * the other way, into the row's tint: it's this icon, flown by the closing page in its own
     * window on top, that is seen on the way home — the row icon under it stays hidden until the
     * flight lands, so blending only the row icon left the colour switching on landing.
     *
     * The first end callback a page sees is its arrival, any later one its return; the page isn't
     * finishing yet during a return (it finishes once the transition has played), so that can't
     * tell them apart.
     */
    private class HeaderIconTint(activity: Activity) : SharedElementCallback() {
        private val page = java.lang.ref.WeakReference(activity)
        private val own = WeakHashMap<ImageView, ColorStateList?>()
        private var arrived = false
        private var rowTint: Int? = null

        /**
         * The page's own header icon is what flies, never one of its rows. A row icon keeps the
         * shared name after [open] flew it into a deeper page — it needs it for that page's return —
         * so by the time this page is left there are two views by that name, and the lookup was free
         * to pick the row: backing out of Comick unmatched and then out of List sync flew the Comick
         * row's icon home instead of List sync's.
         */
        override fun onMapSharedElements(
            names: MutableList<String>?,
            sharedElements: MutableMap<String, View>?,
        ) {
            val activity = page.get() ?: return
            val row = namedIcons[activity] ?: return
            if (sharedElements?.get(ICON_TRANSITION) !== row) return
            val header = findNamed(activity.window.decorView, exclude = row) ?: return
            sharedElements[ICON_TRANSITION] = header
        }

        private fun findNamed(view: View, exclude: View): View? {
            if (view !== exclude && view.transitionName == ICON_TRANSITION && view.isShown) return view
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    findNamed(view.getChildAt(i), exclude)?.let { return it }
                }
            }
            return null
        }

        override fun onSharedElementStart(
            sharedElementNames: MutableList<String>?,
            sharedElements: MutableList<View>?,
            sharedElementSnapshots: MutableList<View>?,
        ) {
            if (arrived) return
            val from = SharedIconTint.forward ?: return
            sharedElements.orEmpty().filterIsInstance<ImageView>().forEach { icon ->
                own[icon] = icon.imageTintList
                icon.imageTintList = ColorStateList.valueOf(from)
            }
        }

        override fun onSharedElementEnd(
            sharedElementNames: MutableList<String>?,
            sharedElements: MutableList<View>?,
            sharedElementSnapshots: MutableList<View>?,
        ) {
            val icons = sharedElements.orEmpty().filterIsInstance<ImageView>()
            if (arrived) {
                val to = rowTint ?: return
                icons.forEach { icon ->
                    val from = icon.imageTintList?.defaultColor ?: return@forEach
                    blendTint(icon, from, ColorStateList.valueOf(to))
                }
                return
            }
            arrived = true
            val from = SharedIconTint.takeForward() ?: return
            rowTint = from
            icons.forEach { icon ->
                if (!own.containsKey(icon)) return@forEach
                val target = own.remove(icon)
                blendTint(icon, from, target)
            }
        }
    }

    private fun sharedAxis(forward: Boolean): Transition =
        MaterialSharedAxis(MaterialSharedAxis.X, forward).apply {
            duration = (PAGE_MS * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong()
            // The bars are part of the window, not the page; moving them with it flashes them.
            excludeTarget(android.R.id.statusBarBackground, true)
            excludeTarget(android.R.id.navigationBarBackground, true)
        }

    private const val PAGE_MS = 300L

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
