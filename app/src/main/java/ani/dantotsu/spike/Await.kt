package ani.dantotsu.spike

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bridges a Play Services [Task] into a coroutine.
 *
 * Eight lines here rather than a dependency on `kotlinx-coroutines-play-services`, which is all
 * that library would be used for. ML Kit's recognizer and translator both hand back [Task], and
 * both are awaited off the main thread, so blocking `Tasks.await` — what TachiyomiAT uses — would
 * hold a thread per call for no reason.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
