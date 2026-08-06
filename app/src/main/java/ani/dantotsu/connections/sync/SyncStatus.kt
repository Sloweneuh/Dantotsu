package ani.dantotsu.connections.sync

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * What cloud sync is doing right now, for anything that wants to show it.
 *
 * Sync is deliberately quiet — it runs in the background and says nothing when it works — which is
 * correct behaviour and terrible feedback. There was no way to tell "off", "working", and "waiting
 * for you" apart without opening the settings screen and reading a timestamp.
 *
 * The transient states are counted rather than set, because several modules push and pull
 * concurrently: settings, per-media progress and extension settings all run their own cycles, so a
 * flag would be cleared by whichever finished first while the others were still going.
 */
object SyncStatus {

    enum class State { Disabled, Synced, Downloading, Uploading, Conflict }

    // Internal rather than private so the inline wrappers below can reach them.
    @PublishedApi internal val uploads = AtomicInteger(0)
    @PublishedApi internal val downloads = AtomicInteger(0)

    private val _state = MutableStateFlow(State.Disabled)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Wraps an upload so the state is right even if it throws.
     *
     * Inline so the wrapped body keeps behaving as part of its own function: the push routines
     * return early from half a dozen places, and wrapping them in an ordinary lambda would have
     * turned every one of those into a compile error or, worse, a silent change of meaning.
     */
    inline fun <T> uploading(block: () -> T): T {
        uploads.incrementAndGet()
        refresh()
        try {
            return block()
        } finally {
            uploads.decrementAndGet()
            refresh()
        }
    }

    /** Wraps a download. Same reasoning as [uploading]. */
    inline fun <T> downloading(block: () -> T): T {
        downloads.incrementAndGet()
        refresh()
        try {
            return block()
        } finally {
            downloads.decrementAndGet()
            refresh()
        }
    }

    /**
     * Recomputes from scratch. Called whenever anything that feeds it changes, and cheap enough to
     * call speculatively — it's two preference reads and a couple of counters.
     *
     * Order matters: being switched off outranks everything, and something needing the user outranks
     * a transfer, because a transfer will finish on its own and a conflict won't.
     */
    fun refresh() {
        _state.value = when {
            !PrefManager.getVal<Boolean>(PrefName.CloudSyncEnabled) || !SyncIdentity.isLinked() ->
                State.Disabled

            SyncConflictNotice.isPending() -> State.Conflict
            downloads.get() > 0 -> State.Downloading
            uploads.get() > 0 -> State.Uploading
            else -> State.Synced
        }
    }
}
