package ani.dantotsu.settings.saving.internal

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

//used to encrypt and decrypt json strings on import and export
class PreferenceKeystore {
    companion object {
        fun generateKey(alias: String) {
            val keyGenerator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")

            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .setRandomizedEncryptionRequired(false)
                    .build()
            )
            keyGenerator.generateKey()
        }

        // .sani v2: MAGIC · version · iterations (int) · salt · iv · AES-GCM ciphertext+tag.
        // v1 files are a bare salt · AES-CBC ciphertext under an all-zero IV, with no tag, so a
        // wrong password or a damaged file only showed up as a padding error or as garbage JSON.
        private val MAGIC = "SANI".toByteArray(Charsets.US_ASCII)
        private const val VERSION: Byte = 2
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val LEGACY_ITERATIONS = 10_000

        // Stored per file, so it can be raised later without breaking old backups. Kept modest
        // because the import dialogs decrypt on the main thread.
        private const val ITERATIONS = 200_000
        private const val HEADER_BYTES = 4 + 1 + 4 + SALT_BYTES + IV_BYTES

        /** A whole .sani file's bytes for [plaintext]. */
        fun encryptBackup(password: CharArray, plaintext: String): ByteArray {
            val salt = generateSalt()
            val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                deriveKeyFromPassword(password, salt, ITERATIONS),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            val header = ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC).put(VERSION).putInt(ITERATIONS).put(salt).put(iv)
                .array()
            return header + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        }

        /** Decrypts a whole .sani file, either format. Throws on a wrong password or damage. */
        fun decryptBackup(password: CharArray, file: ByteArray): String {
            if (file.size > HEADER_BYTES && file.copyOfRange(0, 4).contentEquals(MAGIC)) {
                try {
                    val buf = ByteBuffer.wrap(file, 4, HEADER_BYTES - 4)
                    val version = buf.get()
                    if (version != VERSION) throw IllegalArgumentException("Unknown backup version $version")
                    val iterations = buf.int
                    val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
                    val iv = ByteArray(IV_BYTES).also { buf.get(it) }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        deriveKeyFromPassword(password, salt, iterations),
                        GCMParameterSpec(GCM_TAG_BITS, iv)
                    )
                    return cipher.doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
                        .toString(Charsets.UTF_8)
                } catch (e: Exception) {
                    // A v1 salt can start with the magic bytes by chance (1 in 2^32).
                    return try {
                        decryptLegacy(password, file)
                    } catch (_: Exception) {
                        throw e
                    }
                }
            }
            return decryptLegacy(password, file)
        }

        private fun decryptLegacy(password: CharArray, file: ByteArray): String {
            val salt = file.copyOfRange(0, SALT_BYTES)
            val ciphertext = file.copyOfRange(SALT_BYTES, file.size)
            val secretKey = deriveKeyFromPassword(password, salt, LEGACY_ITERATIONS)
            val cipher =
                Cipher.getInstance("${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_CBC}/${KeyProperties.ENCRYPTION_PADDING_PKCS7}")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                IvParameterSpec(ByteArray(16))
            ) // Use the correct IV
            return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }

        fun generateSalt(): ByteArray {
            val random = SecureRandom()
            val salt = ByteArray(16)
            random.nextBytes(salt)
            return salt
        }

        private fun deriveKeyFromPassword(
            password: CharArray,
            salt: ByteArray,
            iterations: Int,
        ): SecretKey {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password, salt, iterations, 256)
            return SecretKeySpec(factory.generateSecret(spec).encoded, KeyProperties.KEY_ALGORITHM_AES)
        }

    }
}