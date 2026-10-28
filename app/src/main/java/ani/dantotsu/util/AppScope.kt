package ani.dantotsu.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * For work that has to outlive the screen or sheet that started it — a sync the user kicked off
 * and then backed out of. In place of GlobalScope, where an exception escaping a launch takes the
 * whole process down: here it is logged, and the other jobs carry on.
 */
object AppScope : CoroutineScope {
    override val coroutineContext: CoroutineContext =
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Logger.log(e) }
}
