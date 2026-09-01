package ani.dantotsu.util

/**
 * Stand-in for the LeakCanary wiring in `src/leakcanary`, compiled into every build type that
 * does not get the library: `release`, and `debug` (the distributed -beta01 channel, which is
 * marked `debuggable false` — LeakCanary refuses to run in such a build and aborts the process).
 *
 * Same API, does nothing, and pulls in no LeakCanary classes — which is what lets the rest of the
 * app call [install] and [watch] unconditionally instead of guarding every call site.
 */
@Suppress("UNUSED_PARAMETER")
object LeakDetection {
    fun install() = Unit
    fun watch(target: Any?, description: String) = Unit
}
