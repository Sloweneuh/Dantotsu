package ani.dantotsu.connections.sync

import android.util.Base64
import ani.dantotsu.util.Logger
import com.google.android.gms.tasks.Task
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Query
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Shared plumbing for the cloud-sync modules: how long a call may take, how a Firebase task is
// awaited, and what "now" means when every device is stamping its own writes.

/**
 * Ceiling on any single cloud round trip.
 *
 * Realtime Database without disk persistence doesn't fail fast when it can't reach the server — a
 * `get()` waits for a connection rather than erroring, so an offline call simply never completes.
 * That left [SyncPushWorker] burning its whole execution window before WorkManager killed it
 * mid-flight, and left the settings screen's "Sync now" spinner turning with no way to cancel.
 */
private const val SYNC_TIMEOUT_MS = 15_000L

/**
 * Reads this node or query, failing rather than hanging when the database is unreachable.
 *
 * Declared on [Query] rather than [DatabaseReference] so a filtered read
 * (`orderByChild(…).startAt(…)`) goes through the same timeout; a reference is itself a query, so
 * plain reads are unaffected.
 *
 * @throws Exception on failure, timeout, or a rejected read — callers treat any of them the same
 *   way: skip this cycle rather than act on data they couldn't confirm.
 */
internal suspend fun Query.getSnapshot(
    timeoutMs: Long = SYNC_TIMEOUT_MS,
): DataSnapshot = withTimeout(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        get()
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}

/**
 * Whether a payload is small enough to store, checked before it's sealed.
 *
 * The database rules cap each `payload` string, and sealing inflates one by about a third
 * (base64 over `iv || ciphertext || tag`). A payload over the cap isn't rejected by the client — it
 * is rejected by the *server*, on every attempt, so the push fails identically forever while the
 * user is told only that sync failed. Catching it here turns that into one explanatory log line and
 * leaves the rest of the sync working.
 *
 * @param stored what will actually be sealed, i.e. the payload as [SyncCodec] encoded it.
 * @param limit the ceiling for this node, i.e. the rule's cap with the base64 growth already taken
 *   out. Deliberately below the true bound: the point is to notice before the server does, and the
 *   sizes involved are nowhere near it in normal use.
 * @param plaintextLength the size before encoding, reported alongside so an over-limit log says
 *   whether compression was already working on it.
 */
internal fun fitsInNode(
    stored: String,
    limit: Int,
    what: String,
    plaintextLength: Int = stored.length,
): Boolean {
    if (stored.length <= limit) return true
    val raw = if (plaintextLength != stored.length) " (${plaintextLength} uncompressed)" else ""
    Logger.log("$what: payload is ${stored.length} chars$raw, over the $limit limit; not uploading")
    return false
}

/**
 * The highest payload format this build can read, and what [schemaIsReadable] measures a node
 * against. Absent on nodes written before this existed, which reads as version 1.
 *
 * - **1** — the payload is the plaintext JSON.
 * - **2** — the payload is [SyncCodec]-compressed.
 */
internal const val SYNC_SCHEMA_VERSION = 2

/**
 * Compresses a payload that would otherwise be too big to store.
 *
 * Extension settings are the case that needed this: sources keep their own `SharedPreferences`, some
 * of them cache a great deal there, and a real install produced a 1.9 MB export — twice what the node
 * can hold, so the push was refused on every cycle and that account's extension settings simply never
 * synced. It is also JSON full of repeated keys, which is to say roughly a tenth of that once
 * deflated.
 *
 * Only oversized payloads are compressed, never as a general saving. Compressing is what makes a
 * node v2, and a v2 node is one older builds decline to read — so restricting it to payloads that
 * were being rejected anyway means the change can only add nodes those builds can sync, never take
 * one away. A device on an older release keeps reading everything it read before.
 *
 * The encoded form is self-describing on top of that: [decode] recognises the marker, so nodes
 * written before this existed read back unchanged and nothing needs migrating.
 */
internal object SyncCodec {

    /** Prefixes a compressed payload. Not valid JSON, so it can't be mistaken for a plain one. */
    private const val MARKER = "gz1:"

    /** A payload in its stored form, with the schema version that form makes the node. */
    data class Encoded(val text: String, val version: Int)

    /**
     * @param limit the node's ceiling — a payload already under it is left exactly as it is.
     * @return what to seal, and the version it makes the node.
     */
    fun encode(plaintext: String, limit: Int): Encoded {
        val plain = Encoded(plaintext, 1)
        if (plaintext.length <= limit) return plain
        val packed = runCatching {
            val deflated = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(plaintext.toByteArray(StandardCharsets.UTF_8)) }
            }.toByteArray()
            MARKER + Base64.encodeToString(deflated, Base64.NO_WRAP)
        }.getOrElse {
            Logger.log("SyncCodec: compression failed, storing as-is: ${it.message}")
            return plain
        }
        // Already-compressed contents (a source caching an image, say) can come out bigger. Nothing
        // is gained by storing those deflated, and the size check downstream refuses them either way.
        if (packed.length >= plaintext.length) return plain
        Logger.log("SyncCodec: compressed ${plaintext.length} chars to ${packed.length}")
        return Encoded(packed, 2)
    }

    /**
     * Reverses [encode]. @return null when a payload claims to be compressed but can't be
     * decompressed — the same answer as a payload that wouldn't decrypt, and treated the same way.
     */
    fun decode(stored: String): String? {
        if (!stored.startsWith(MARKER)) return stored
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(MARKER), Base64.NO_WRAP)
            GZIPInputStream(ByteArrayInputStream(raw)).use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }
        }.getOrElse {
            Logger.log("SyncCodec: could not decompress payload: ${it.message}")
            null
        }
    }
}

/**
 * Prepares [plaintext] for storage: compressed if it has to be, size-checked against this node's
 * rule, then sealed — and tagged with the schema version the result actually is.
 *
 * Returns the fields every node shares; callers holding extra ones (CloudSync's device label) add
 * them to what comes back. @return null when the payload can't be stored, which callers treat as
 * "skip this push" rather than as an error worth retrying.
 */
internal fun storedEnvelope(
    plaintext: String,
    limit: Int,
    what: String,
    ts: Long,
): MutableMap<String, Any?>? {
    val encoded = SyncCodec.encode(plaintext, limit)
    if (!fitsInNode(encoded.text, limit, what, plaintext.length)) return null
    val sealed = SyncIdentity.seal(encoded.text) ?: return null
    return mutableMapOf("payload" to sealed, "ts" to ts, "v" to encoded.version)
}

/** The version a node claims, defaulting to the pre-versioning format. */
internal fun DataSnapshot.schemaVersion(): Int =
    child("v").getValue(Int::class.java) ?: 1

/**
 * True when this build understands a node's format. A newer payload is left strictly alone: not
 * applied, and not overwritten either, since the device that wrote it knows something this one
 * doesn't.
 */
internal fun DataSnapshot.schemaIsReadable(what: String): Boolean {
    val version = schemaVersion()
    if (version <= SYNC_SCHEMA_VERSION) return true
    Logger.log("$what: node is schema v$version, this build reads v$SYNC_SCHEMA_VERSION; ignoring")
    return false
}

/** Plaintext ceilings matching the deployed rules, with the base64 growth removed. */
internal object NodeLimits {
    const val SETTINGS = 900_000
    const val EXTENSIONS = 180_000
    const val EXTENSION_SETTINGS = 900_000
    const val UNREAD = 900_000
    const val PROGRESS_MEDIA = 90_000
}

/**
 * Awaits a write. @return false on failure or timeout — never throws.
 *
 * A timed-out write is abandoned here but may still land: the SDK keeps it queued. That's why the
 * pushes have to stay idempotent — a retry can re-send state the first attempt did eventually
 * deliver, and the two must be able to arrive in either order.
 */
internal suspend fun Task<Void>.awaitOk(timeoutMs: Long = SYNC_TIMEOUT_MS): Boolean = runCatching {
    withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { cont.resume(true) }
                .addOnFailureListener { cont.resume(false) }
        }
    }
}.getOrDefault(false)

/**
 * What a background push did, so [SyncPushWorker] can tell "nothing needed doing" from "the network
 * ate it". Only [Failed] is worth retrying: a push that found no local changes, or that backed off
 * because the cloud diverged, will reach the same conclusion 30 seconds later.
 */
enum class PushResult { NothingToDo, Pushed, Failed }

/** How a [compareAndSet] ended. */
internal enum class CasOutcome {
    /** The node was ours to replace, and now holds the new value. */
    Written,

    /** Another device got there first; nothing was written and nothing was lost. */
    Superseded,

    /** Unreachable, timed out, or rejected. */
    Failed,
}

/**
 * Replaces this node's contents, but only if [accept] approves what is currently there — as one
 * indivisible step.
 *
 * The pushes used to read the node, decide the cloud hadn't moved, and then write. Two devices
 * backgrounding in the same moment both read "unchanged", both wrote, and the slower one's session
 * vanished — while it recorded a baseline claiming it had won, so nothing ever noticed or repaired
 * it. Deciding and writing in the same operation closes that window: the loser is told it lost.
 *
 * [accept] runs on the database's own thread and may be called more than once as the transaction
 * retries against fresher data, so it must not have side effects. It receives the node as it
 * currently stands — absent entirely on a first write.
 */
internal suspend fun DatabaseReference.compareAndSet(
    value: Map<String, Any?>,
    timeoutMs: Long = SYNC_TIMEOUT_MS,
    accept: (MutableData) -> Boolean,
): CasOutcome = runCatching {
    withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    if (!accept(currentData)) return Transaction.abort()
                    // Nulls would be read as "delete this child"; drop them so an absent optional
                    // field stays absent rather than becoming a removal instruction.
                    currentData.value = value.filterValues { it != null }
                    return Transaction.success(currentData)
                }

                override fun onComplete(
                    error: DatabaseError?,
                    committed: Boolean,
                    snapshot: DataSnapshot?,
                ) {
                    if (!cont.isActive) return
                    cont.resume(
                        when {
                            error != null -> {
                                Logger.log("compareAndSet: ${error.message}")
                                CasOutcome.Failed
                            }

                            committed -> CasOutcome.Written
                            else -> CasOutcome.Superseded
                        }
                    )
                }
            }, false) // no local listeners on these nodes, so don't fire events for them
        }
    }
}.getOrDefault(CasOutcome.Failed)

/**
 * The clock the sync modules stamp their writes with.
 *
 * Every `ts` in the cloud is written by one device, and every comparison against it ("is the remote
 * newer than what I last synced?") is made by another. With raw [System.currentTimeMillis] that
 * arithmetic is only as good as the two clocks agree, and when it fails it never recovers: a device
 * running fast writes a timestamp in the future, so every other device reads the cloud as
 * permanently newer — their pushes bail as divergent and their pulls keep overwriting them. A slow
 * clock is the same failure mirrored, and can never win a conflict.
 *
 * So apply the offset the Realtime Database already knows between this device and the server.
 * `.info/serverTimeOffset` is a client-side path the SDK maintains from the connection it already
 * has: reading it issues no request and needs no rule access.
 *
 * It is read one-shot rather than observed. A permanent listener would resolve the offset sooner,
 * but it would also hold the websocket open for as long as the app is running — and these modules
 * are deliberately built on nothing but `get()`/`setValue()` so that an idle install costs no
 * connection at all, which is what keeps the whole user base inside the free tier's simultaneous
 * connection cap.
 */
internal object SyncClock {

    /** Both paths are local, so these bound a hang rather than a round trip. */
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val OFFSET_TIMEOUT_MS = 3_000L

    @Volatile private var offsetMs = 0L
    @Volatile private var resolved = false

    /** Server-relative wall clock, in the same units as [System.currentTimeMillis]. */
    suspend fun now(): Long = System.currentTimeMillis() + offset()

    /**
     * [now] without the refresh, for the main thread. Timestamps in the cloud are server-relative,
     * so rendering "saved 3 minutes ago" against the raw local clock would put a skewed device's
     * error back on screen — in the one dialog where the user is deciding which copy is newer. A
     * sync has always just run by the time any of this is displayed, so the cached offset is current.
     */
    fun nowCached(): Long = System.currentTimeMillis() + offsetMs

    private suspend fun offset(): Long {
        // The offset is only meaningful once the SDK has actually reached the server — until then
        // it reads as zero, which is the uncorrected local clock this exists to avoid. Most pushes
        // read the cloud before they write and so are connected by the time they stamp, but not
        // all of them (ProgressSync writes without reading first), and the first write of a fresh
        // worker process is exactly the one that would go out unstamped. So wait for the
        // connection once; after that the cached offset carries every later call for free.
        if (!resolved && awaitConnected()) resolved = true
        readOffset()
        return offsetMs
    }

    /** @return true once the database reports a live connection, false on timeout or error. */
    private suspend fun awaitConnected(): Boolean = runCatching {
        withTimeout(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val ref = FirebaseDatabase.getInstance().getReference(".info/connected")
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        // Fires immediately with false, then again on connect. Ignore the first.
                        if (snapshot.getValue(Boolean::class.java) != true) return
                        ref.removeEventListener(this)
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        ref.removeEventListener(this)
                        if (cont.isActive) cont.resumeWithException(error.toException())
                    }
                }
                ref.addValueEventListener(listener)
                // Detach on timeout too — this must never outlive the call that opened it.
                cont.invokeOnCancellation { runCatching { ref.removeEventListener(listener) } }
            }
        }
    }.getOrDefault(false)

    private suspend fun readOffset() {
        runCatching {
            withTimeout(OFFSET_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    FirebaseDatabase.getInstance().getReference(".info/serverTimeOffset")
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                cont.resume(snapshot.getValue(Long::class.java) ?: 0L)
                            }

                            override fun onCancelled(error: DatabaseError) {
                                cont.resumeWithException(error.toException())
                            }
                        })
                }
            }
        }.onSuccess { fresh ->
            if (fresh != offsetMs) {
                offsetMs = fresh
                Logger.log("SyncClock: server offset ${fresh}ms")
            }
        }.onFailure {
            // Keep the last known offset rather than snapping back to the raw local clock.
            Logger.log("SyncClock: offset read failed: ${it.message}")
        }
    }
}
