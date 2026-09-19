package ani.dantotsu.settings

import ani.dantotsu.parsers.novel.NovelExtensionManager
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Finding which extensions have updates, and installing them one after another.
 *
 * Both halves used to live inside [ExtensionUpdatesFragment], which meant a scheduled update could
 * only be had by opening the screen that owned it. Nothing here touches a view: the sequence
 * reports what it is doing through [Progress] and leaves presenting it — a spinning row, a
 * notification, nothing at all — to whoever collected it.
 *
 * Updates run strictly one at a time. Each install hands an apk to a single foreground service
 * whose queue is shared, and letting several in at once interleaves their progress reporting
 * against one queue without making any of them finish sooner.
 */
object ExtensionUpdateRunner {

    sealed interface Progress {
        val item: UpdateItem

        data class Started(override val item: UpdateItem, val index: Int, val total: Int) : Progress

        /**
         * The install reached a terminal step. [step] distinguishes them: installed, cancelled,
         * refused without confirmation, or failed — see [InstallStep].
         */
        data class Finished(override val item: UpdateItem, val step: InstallStep?) : Progress

        /** The install stream itself broke, as opposed to reporting a terminal step. */
        data class Failed(override val item: UpdateItem, val error: Throwable) : Progress
    }

    /**
     * Every installed extension currently carrying an update, across all three media types.
     *
     * Reads the already-fetched flows rather than hitting the repos; callers that need those fresh
     * refresh them first.
     */
    fun pendingUpdates(): List<UpdateItem> {
        val animeManager: AnimeExtensionManager = Injekt.get()
        val mangaManager: MangaExtensionManager = Injekt.get()
        val novelManager: NovelExtensionManager = Injekt.get()

        // What each update would install, looked up from the repo listing by package. The installed
        // entry only knows that an update exists, not what version it is.
        val animeVersions = animeManager.availableExtensionsFlow.value
            .associate { it.pkgName to it.versionName }
        val mangaVersions = mangaManager.availableExtensionsFlow.value
            .associate { it.pkgName to it.versionName }
        val novelVersions = novelManager.availableExtensionsFlow.value
            .associate { it.pkgName to it.versionName }

        val animeUpdates = animeManager.installedExtensionsFlow.value
            .filter { it.hasUpdate }
            .map { UpdateItem.AnimeUpdate(it, animeVersions[it.pkgName]) }

        val mangaUpdates = mangaManager.installedExtensionsFlow.value
            .filter { it.hasUpdate }
            .map { UpdateItem.MangaUpdate(it, mangaVersions[it.pkgName]) }

        val novelUpdates = novelManager.installedExtensionsFlow.value
            .filter { it.hasUpdate }
            .map { UpdateItem.NovelUpdate(it, novelVersions[it.pkgName]) }

        return animeUpdates + mangaUpdates + novelUpdates
    }

    /**
     * Installs [items] in order, emitting progress as it goes.
     *
     * One extension failing does not stop the rest: a broken repo entry or a refused install is
     * reported and the sequence moves on, which is the behaviour the update-all button already had.
     *
     * @param unattended True when no one is watching the screen. See [eu.kanade.tachiyomi.extension.installer.Installer.Entry.unattended].
     */
    fun run(items: List<UpdateItem>, unattended: Boolean): Flow<Progress> = flow {
        items.forEachIndexed { index, item ->
            emit(Progress.Started(item, index, items.size))
            try {
                emit(Progress.Finished(item, awaitLastStep(updateObservable(item, unattended))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emit(Progress.Failed(item, e))
            }
        }
    }

    private fun updateObservable(item: UpdateItem, unattended: Boolean): Observable<InstallStep> =
        when (item) {
            is UpdateItem.AnimeUpdate ->
                Injekt.get<AnimeExtensionManager>().updateExtension(item.extension, unattended)

            is UpdateItem.MangaUpdate ->
                Injekt.get<MangaExtensionManager>().updateExtension(item.extension, unattended)

            is UpdateItem.NovelUpdate ->
                Injekt.get<NovelExtensionManager>().updateExtension(item.extension, unattended)
        }

    /**
     * Suspends until the install stream ends, and answers with the last step it reported.
     *
     * The stream is taken until its first completed step, so that last value is the outcome —
     * completion alone says nothing, since cancelling and failing end it just as success does.
     * Null means it completed without reporting anything, which an unknown package does.
     */
    private suspend fun awaitLastStep(observable: Observable<InstallStep>): InstallStep? =
        suspendCancellableCoroutine { continuation ->
            var lastStep: InstallStep? = null
            val subscription = observable.subscribe(
                { step -> lastStep = step },
                { error -> if (continuation.isActive) continuation.resumeWithException(error) },
                { if (continuation.isActive) continuation.resume(lastStep) },
            )
            continuation.invokeOnCancellation { subscription.unsubscribe() }
        }
}
