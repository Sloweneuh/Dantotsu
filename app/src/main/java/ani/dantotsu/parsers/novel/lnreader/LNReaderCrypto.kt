package ani.dantotsu.parsers.novel.lnreader

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM for the `@libs/aes` shim.
 *
 * Upstream re-exports `gcm` from `@noble/ciphers`; rather than run that library inside the engine,
 * the JS side keeps the same `gcm(key, nonce).encrypt/decrypt` shape and the cipher itself comes
 * from the platform. Bytes cross the bridge base64-encoded, since the engine's typed arrays do not
 * marshal directly.
 *
 * Only one published plugin uses this, and the spike has not exercised it against that plugin's
 * real payloads — the tag length in particular is the conventional 128 bits rather than something
 * verified against upstream.
 */
object LNReaderCrypto {

    private const val TAG_BITS = 128

    fun gcm(
        mode: String,
        keyB64: String,
        nonceB64: String,
        dataB64: String,
        aadB64: String?,
    ): String = try {
        val key = SecretKeySpec(decode(keyB64), "AES")
        val spec = GCMParameterSpec(TAG_BITS, decode(nonceB64))
        val opmode = if (mode == "encrypt") Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(opmode, key, spec)
            aadB64?.takeIf { it.isNotEmpty() }?.let { updateAAD(decode(it)) }
        }
        encode(cipher.doFinal(decode(dataB64)))
    } catch (e: Exception) {
        // Surfaced as an exception on the JS side rather than silently returning empty bytes,
        // which would look like a successful decrypt of nothing.
        throw LNReaderPluginException("AES-GCM $mode failed: ${e.message}")
    }

    private fun decode(b64: String): ByteArray = Base64.decode(b64, Base64.NO_WRAP)

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
