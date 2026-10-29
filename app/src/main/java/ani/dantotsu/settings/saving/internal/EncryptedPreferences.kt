package ani.dantotsu.settings.saving.internal

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ani.dantotsu.util.Logger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A [SharedPreferences] whose values are encrypted at rest, for the Protected location — the
 * account tokens.
 *
 * Those used to sit in a plain XML file, readable by anything that could read the app's data: a
 * rooted device, a debuggable build over `run-as`, a backup of the data directory. Here each value
 * is sealed with AES-GCM under a key that lives in the Android Keystore and never leaves it, so the
 * file on disk ([fileFor]) is useless off this device. Key names stay readable; they say nothing a
 * user's own settings screen doesn't.
 *
 * Values are decrypted once, at open, into the in-memory map [MapBackedPreferences] serves reads
 * from, and encrypted again only as they change.
 *
 * The first open moves the old plain file's contents in, and empties that file once the move is
 * stored. If the Keystore can't be used, [open] throws and the caller keeps the plain file — a
 * device that can't encrypt still signs in.
 */
class EncryptedPreferences private constructor(
    private val store: SharedPreferences,
    private val key: SecretKey,
    initial: Map<String, Any>,
) : MapBackedPreferences(initial, "EncryptedPreferences") {

    override fun persist(cleared: Boolean, writes: Map<String, Any?>): Boolean = try {
        val editor = store.edit()
        if (cleared) {
            // Everything but the migration marker, which says the plain file is already moved.
            store.all.keys.filter { it != MIGRATED }.forEach { editor.remove(it) }
        }
        for ((name, value) in writes) {
            if (value == null) editor.remove(name)
            else encrypt(key, value)?.let { editor.putString(name, it) }
        }
        editor.commit()
    } catch (e: Exception) {
        Logger.log("EncryptedPreferences: write failed: ${e.message}")
        false
    }

    companion object {
        private const val FILE_SUFFIX = "_encrypted"
        private const val KEY_ALIAS = "dantotsu_protected_prefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val MIGRATED = "__migrated_from_plain"

        /** The encrypted file for [name]; listed in the backup rules beside the plain one. */
        fun fileFor(name: String) = name + FILE_SUFFIX

        /**
         * Opens the encrypted store for the plain preferences file [name], moving that file's
         * contents in on the first open. Throws when the Keystore can't be used.
         */
        fun open(context: Context, name: String): EncryptedPreferences {
            val key = keystoreKey()
            val store = context.getSharedPreferences(fileFor(name), Context.MODE_PRIVATE)
            if (!store.contains(MIGRATED)) migrate(context, name, store, key)
            val values = HashMap<String, Any>()
            store.all.forEach { (k, v) ->
                if (k == MIGRATED) return@forEach
                val decrypted = (v as? String)?.let { decrypt(key, it) }
                if (decrypted != null) values[k] = decrypted
                else Logger.log("EncryptedPreferences: dropping unreadable $k")
            }
            return EncryptedPreferences(store, key, values)
        }

        private fun migrate(context: Context, name: String, store: SharedPreferences, key: SecretKey) {
            val plain = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = store.edit()
            var moved = 0
            plain.all.forEach { (k, v) ->
                val sealed = v?.let { encrypt(key, it) } ?: return@forEach
                editor.putString(k, sealed)
                moved++
            }
            editor.putLong(MIGRATED, System.currentTimeMillis())
            // Only once the encrypted copy is on disk does the plain one go.
            if (!editor.commit()) throw IllegalStateException("Could not store the encrypted copy")
            plain.edit().clear().commit()
            Logger.log("EncryptedPreferences: moved $moved values out of $name.xml")
        }

        private fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }

        /** base64 of `iv || ciphertext+tag` over the value's [PrefValueCodec] form; null if unstorable. */
        private fun encrypt(key: SecretKey, value: Any): String? {
            val (type, text) = PrefValueCodec.encode(value) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val sealed = cipher.doFinal("$type\n$text".toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
        }

        private fun decrypt(key: SecretKey, stored: String): Any? = try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            val plain = cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES).toString(Charsets.UTF_8)
            val split = plain.indexOf('\n')
            PrefValueCodec.decode(plain.substring(0, split).toInt(), plain.substring(split + 1))
        } catch (e: Exception) {
            null
        }
    }
}
