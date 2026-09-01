package ani.dantotsu.util

import leakcanary.AppWatcher
import leakcanary.LeakCanary

/**
 * LeakCanary, tuned for chasing the OOM crashes rather than for everyday use.
 *
 * Compiled only into the debuggable build types (`alpha`, `debug`); `release` gets the no-op
 * twin in `src/release`, so nothing here — and no LeakCanary class — reaches a shipped APK.
 *
 * The library installs itself from its own ContentProvider before [App.onCreate] runs, and from
 * then on watches every destroyed Activity, Fragment, fragment View, ViewModel and Service on its
 * own. All this object adds is configuration plus [watch], for the objects LeakCanary cannot know
 * about — the ones in this app that hold megabytes each and are released by hand.
 */
object LeakDetection {

    fun install() {
        LeakCanary.config = LeakCanary.config.copy(
            // Default is 5: LeakCanary sits on retained objects until that many pile up while the
            // app is visible. When the symptom is an OOM, one retained ExoplayerView or reader
            // Activity is already the whole story, so dump on the first.
            retainedVisibleThreshold = 1,
            // The reason for this exercise. Without it a report names the leaking object but not
            // how much heap it pins, and that number is the only thing separating the leak that
            // causes the OOM from the handful of harmless ones every app has.
            computeRetainedHeapSize = true,
            // `alpha` is debuggable and usually run from the IDE; left at the default, an attached
            // debugger silently suppresses every heap dump.
            dumpHeapWhenDebugging = true,
        )
    }

    /**
     * Assert that [target] should now be garbage collectable, and report it as a leak if it is
     * still reachable a few seconds later.
     *
     * For objects released manually rather than by a lifecycle callback — call it at the point
     * the last owner lets go, never before.
     */
    fun watch(target: Any?, description: String) {
        if (target == null) return
        AppWatcher.objectWatcher.expectWeaklyReachable(target, description)
    }
}
