package ani.dantotsu.settings.saving.internal

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class PreferenceKeystoreTest {

    companion object {
        init {
            // Android's provider answers "AES/CBC/PKCS7Padding"; the JVM's doesn't.
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val password = "hunter2".toCharArray()
    private val json = """{"ani.dantotsu.general":{"Theme":{"type":"String","value":"Dark"}}}"""

    @Test
    fun `v2 round-trips`() {
        val file = PreferenceKeystore.encryptBackup(password, json)
        assertEquals(json, PreferenceKeystore.decryptBackup(password, file))
    }

    @Test
    fun `v2 starts with the magic header`() {
        val file = PreferenceKeystore.encryptBackup(password, json)
        assertEquals("SANI", String(file.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(2.toByte(), file[4])
    }

    @Test
    fun `v2 uses a fresh salt and iv each time`() {
        val a = PreferenceKeystore.encryptBackup(password, json)
        val b = PreferenceKeystore.encryptBackup(password, json)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `v2 rejects a wrong password`() {
        val file = PreferenceKeystore.encryptBackup(password, json)
        assertThrows(Exception::class.java) {
            PreferenceKeystore.decryptBackup("wrong".toCharArray(), file)
        }
    }

    @Test
    fun `v2 rejects a tampered file`() {
        val file = PreferenceKeystore.encryptBackup(password, json)
        file[file.size - 1] = (file[file.size - 1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { PreferenceKeystore.decryptBackup(password, file) }
    }

    /** A file as builds before v2 wrote it: salt, then AES-CBC under a zero IV, 10 000 rounds. */
    @Test
    fun `v1 files still import`() {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, 10_000, 256)).encoded
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        val legacy = salt + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        assertEquals(json, PreferenceKeystore.decryptBackup(password, legacy))
    }
}
