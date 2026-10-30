package ani.dantotsu.connections.handoff

import android.net.Uri
import android.os.Build
import android.util.Base64
import ani.dantotsu.connections.sync.SyncCrypto
import ani.dantotsu.media.manga.translation.await
import ani.dantotsu.util.Logger
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.gson.Gson
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Signing a TV in from a phone that's already signed in: the TV shows a QR, the phone scans it,
 * confirms, and hands over its AniList session — typing a password with a remote is the thing this
 * spares the user.
 *
 * The exchange rides on [CloudHandoff]'s dead-drop (same `handoffs/<code>` node, same code shape
 * the database rules accept, so it needs nothing new on the server). What goes in it is sealed
 * with a 256-bit key that exists only in the QR: the database holds ciphertext keyed by a short
 * code, and the code alone opens nothing. The TV deletes the drop as soon as it has read it.
 */
object TvLogin {

    const val HOST = "tvlogin"
    private const val ROOT = "handoffs"
    /** Marks a drop as a sign-in rather than a media handoff, so neither side mistakes the other's. */
    private const val PREFIX = "tvlogin:"
    /** How long a code shown on the TV is good for; it shows a new one after. */
    const val TTL_MS = 10 * 60 * 1000L

    /** What the TV shows: where to drop the session, and the key to seal it with. */
    class Session(val code: String, val key: ByteArray, val createdAt: Long = System.currentTimeMillis()) {
        val expired: Boolean get() = System.currentTimeMillis() - createdAt > TTL_MS

        fun link(tvName: String): String = Uri.Builder()
            .scheme(HandoffPayload.SCHEME)
            .authority(HOST)
            .appendQueryParameter("c", code)
            .appendQueryParameter("k", Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            .appendQueryParameter("n", tvName)
            .build()
            .toString()
    }

    /** A scanned TV's request, as the phone reads it from the QR. */
    class Request(val code: String, val key: ByteArray, val tvName: String)

    /** What the phone hands over: its AniList token and, when it has one, its cloud sync code. */
    data class Grant(val anilistToken: String, val syncCode: String? = null)

    fun newSession(): Session {
        val code = (1..CloudHandoff.CODE_LENGTH)
            .map { CloudHandoff.CODE_ALPHABET[Random.nextInt(CloudHandoff.CODE_ALPHABET.length)] }
            .joinToString("")
        return Session(code, ByteArray(32).also { SecureRandom().nextBytes(it) })
    }

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "TV" }

    fun isLoginLink(uri: Uri?): Boolean = uri?.scheme == HandoffPayload.SCHEME && uri.host == HOST

    fun parse(uri: Uri?): Request? {
        if (!isLoginLink(uri)) return null
        val code = uri!!.getQueryParameter("c")?.uppercase() ?: return null
        if (code.length != CloudHandoff.CODE_LENGTH || code.any { it !in CloudHandoff.CODE_ALPHABET }) return null
        val key = runCatching {
            Base64.decode(uri.getQueryParameter("k"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull()?.takeIf { it.size == 32 } ?: return null
        return Request(code, key, uri.getQueryParameter("n")?.takeIf { it.isNotBlank() } ?: "TV")
    }

    private fun node(code: String) = FirebaseDatabase.getInstance().reference.child(ROOT).child(code)

    /** The phone's half: seals [grant] for the TV that showed [request]. @return whether it was stored. */
    suspend fun send(request: Request, grant: Grant): Boolean = try {
        val sealed = SyncCrypto.seal(request.key, Gson().toJson(grant))
        node(request.code).setValue(mapOf("payload" to PREFIX + sealed, "ts" to ServerValue.TIMESTAMP)).await()
        true
    } catch (e: Exception) {
        Logger.log("TvLogin: couldn't send: ${e.message}")
        false
    }

    /**
     * The TV's half: the grant dropped for [session], or null while there's none yet. A drop it
     * can read is removed at once; one it can't (not a sign-in, or sealed with another key) is left.
     */
    suspend fun receive(session: Session): Grant? = try {
        val snapshot = node(session.code).get().await()
        val payload = snapshot.child("payload").getValue(String::class.java)
        val grant = payload?.takeIf { it.startsWith(PREFIX) }
            ?.let { SyncCrypto.open(session.key, it.removePrefix(PREFIX)) }
            ?.let { runCatching { Gson().fromJson(it, Grant::class.java) }.getOrNull() }
            ?.takeIf { it.anilistToken.isNotBlank() }
        if (grant != null) runCatching { node(session.code).removeValue().await() }
        grant
    } catch (e: Exception) {
        Logger.log("TvLogin: couldn't check: ${e.message}")
        null
    }
}
