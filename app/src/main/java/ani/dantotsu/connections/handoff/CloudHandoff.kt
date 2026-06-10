package ani.dantotsu.connections.handoff

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlin.random.Random

/**
 * Cloud "sharing code" fallback for handing a payload between two devices that can't reach each
 * other over the local transports (Nearby/LAN) — notably WSA, emulators, or devices on networks
 * that block peer discovery. The sender uploads the payload to a Firebase Realtime Database node
 * keyed by a short, human-typeable code; the receiver pulls it back by entering that code.
 *
 * Unlike the discovery transports this is a one-shot dead-drop: there's no pairing, the code is
 * consumed (deleted) on the first successful fetch, and an unclaimed code is treated as expired
 * after [TTL_MS] (and dropped the next time it's looked up).
 *
 * Requires the Realtime Database to be enabled for the project (Google Play flavour only). All
 * callbacks are delivered on the main thread.
 */
object CloudHandoff {

    private const val ROOT = "handoffs"
    private const val TTL_MS = 10 * 60 * 1000L // 10 minutes
    // Ambiguous glyphs (0/O, 1/I) are dropped so a code that's read aloud or typed is unambiguous.
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 6

    private fun root() = FirebaseDatabase.getInstance().reference.child(ROOT)

    private fun newCode(): String =
        (1..CODE_LENGTH).map { CODE_ALPHABET[Random.nextInt(CODE_ALPHABET.length)] }.joinToString("")

    /** Uploads [payload] and returns the generated sharing code via [onResult] (null on failure). */
    fun upload(payload: HandoffPayload, onResult: (String?) -> Unit) {
        val code = newCode()
        runCatching {
            root().child(code)
                .setValue(mapOf("payload" to payload.toJson(), "ts" to ServerValue.TIMESTAMP))
                .addOnSuccessListener { onResult(code) }
                .addOnFailureListener { onResult(null) }
        }.onFailure { onResult(null) }
    }

    /**
     * Fetches and consumes the payload for [code]. Returns null via [onResult] when the code is
     * unknown, expired, malformed, or the database is unreachable.
     */
    fun fetch(code: String, onResult: (HandoffPayload?) -> Unit) {
        val node = runCatching { root().child(code.trim().uppercase()) }.getOrNull()
            ?: return onResult(null)
        node.get()
            .addOnSuccessListener { snapshot ->
                val json = snapshot.child("payload").getValue(String::class.java)
                val ts = snapshot.child("ts").getValue(Long::class.java)
                val payload = json?.let { HandoffPayload.fromJson(it) }
                val expired = ts != null && System.currentTimeMillis() - ts > TTL_MS
                if (payload == null || expired) {
                    if (snapshot.exists()) node.removeValue() // drop a stale/garbage entry
                    onResult(null)
                } else {
                    node.removeValue() // one-shot: consume on first successful read
                    onResult(payload)
                }
            }
            .addOnFailureListener { onResult(null) }
    }
}
