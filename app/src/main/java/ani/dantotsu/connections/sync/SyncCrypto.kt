package ani.dantotsu.connections.sync

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography behind cloud sync: turning a sync code into keys, deriving the
 * database path from one, and sealing payloads with the other.
 *
 * The design constraint is that there is no account and no server-side component, so the database
 * has to be assumed readable and writable by anyone. Everything here follows from that:
 *
 *  - **The path is a secret.** A node lives at an HMAC of the account id under a key only the
 *    user's devices hold, so the tree can't be walked or guessed. The AniList id it was keyed by
 *    is a small public integer, which is what made every user's data reachable by counting.
 *  - **The payload is sealed.** AES-GCM, so a node that is somehow located still yields nothing,
 *    and can't be tampered with — the auth tag is what stops a forged settings blob being written
 *    into someone's device.
 *  - **The key never transits the network.** The sync code *is* the secret material; showing it as
 *    a QR is only a faster way to type it. Nothing derived from it is ever uploaded.
 */
internal object SyncCrypto {

    /**
     * Letters and digits minus the pairs that get misread aloud or in a font: I/1, O/0. Same
     * alphabet as the handoff sharing code, for the same reason — someone is going to read this
     * off one screen and type it into another.
     */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** 15 random characters over a 32-symbol alphabet: 75 bits, well past brute force. */
    private const val DATA_CHARS = 15
    /** Plus one character derived from the rest, so a typo is caught here and not by silence. */
    const val CODE_CHARS = DATA_CHARS + 1

    private const val HKDF_SALT = "dantotsu-cloud-sync-v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private val random = SecureRandom()

    // ---- sync codes ----

    /** A fresh code, in canonical (unseparated, uppercase) form. */
    fun newCode(): String {
        val data = (1..DATA_CHARS)
            .map { ALPHABET[random.nextInt(ALPHABET.length)] }
            .joinToString("")
        return data + checksumChar(data)
    }

    /**
     * Accepts a code as a human might have typed it — lowercase, spaced, hyphenated — and returns
     * it in canonical form, or null if it isn't a well-formed code.
     *
     * The checksum is the point: link with a mistyped code and the derived path simply points
     * somewhere else in the database, so instead of an error the user gets a silent, permanently
     * empty cloud. Catching it here turns that into "that code doesn't look right".
     */
    fun normalizeCode(input: String): String? {
        val cleaned = input.uppercase().filter { it in ALPHABET }
        if (cleaned.length != CODE_CHARS) return null
        val data = cleaned.take(DATA_CHARS)
        if (cleaned.last() != checksumChar(data)) return null
        return cleaned
    }

    /** How a code is grouped wherever it's shown or typed: `ABCD-EFGH-JKLM-NPQR`. */
    const val GROUP_CHARS = 4

    /** Groups a canonical code for display: `ABCD-EFGH-JKLM-NPQR`. */
    fun formatCode(code: String): String = code.chunked(GROUP_CHARS).joinToString("-")

    private fun checksumChar(data: String): Char {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(StandardCharsets.US_ASCII))
        return ALPHABET[(digest[0].toInt() and 0xFF) % ALPHABET.length]
    }

    // ---- secret material ----

    /**
     * The input keying material a code stands for.
     *
     * The characters are used as-is rather than unpacked back into 75 raw bits: HKDF extracts the
     * entropy either way, and skipping the bit-packing removes a whole class of encoding bug from
     * something that must produce byte-identical keys on every device forever.
     */
    fun secretFromCode(code: String): ByteArray =
        code.take(DATA_CHARS).toByteArray(StandardCharsets.US_ASCII)

    /**
     * HKDF-SHA256. One [info] label per purpose, so the key that names the path can't also open the
     * payload — a leaked path reveals nothing about the contents.
     */
    fun derive(secret: ByteArray, info: String, length: Int = 32): ByteArray {
        val prk = hmac(HKDF_SALT.toByteArray(StandardCharsets.UTF_8), secret)
        // Single-block expand: every caller wants 32 bytes, which is exactly one SHA-256 output.
        val block = hmac(prk, info.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(1))
        return block.copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    // ---- path derivation ----

    /**
     * The database node name for [scope] under [pathKey]: an HMAC, hex, truncated to 128 bits —
     * unguessable, stable, and legal as a Firebase key.
     */
    fun pathId(pathKey: ByteArray, scope: String): String =
        hmac(pathKey, scope.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)

    // ---- payload sealing ----

    /** AES-GCM with a fresh IV, returned as base64 of `iv || ciphertext || tag`. */
    fun seal(dataKey: ByteArray, plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(dataKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        val body = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    /**
     * Reverses [seal]. @return null when the input isn't ours to read — a payload written under a
     * different key, a truncated node, or one that was tampered with (GCM's tag fails closed).
     * Every caller treats that the same way as an absent node.
     */
    fun open(dataKey: ByteArray, sealed: String): String? = runCatching {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(dataKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, raw.copyOf(IV_BYTES)),
        )
        String(
            cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size)),
            StandardCharsets.UTF_8,
        )
    }.getOrNull()
}
