package dev.firetvonwatch.data

import dev.firetvonwatch.data.adb.AdbKeyStore
import dev.firetvonwatch.data.adb.SecretWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdbKeyStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** XOR "encryption" stands in for Keystore AES-GCM in JVM tests. */
    private class XorWrapper : SecretWrapper {
        var broken = false
        override fun wrap(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun unwrap(wrapped: ByteArray): ByteArray {
            check(!broken) { "keystore key gone" }
            return wrap(wrapped)
        }
    }

    @Test fun `key persists across instances and private key is not stored in clear`() {
        val dir = tmp.newFolder()
        val wrapper = XorWrapper()
        val first = AdbKeyStore(dir, wrapper).fingerprint()
        val second = AdbKeyStore(dir, wrapper).fingerprint()
        assertEquals(first, second)
        val stored = File(dir, "adbkey.enc").readBytes()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("PRIVATE KEY"))
    }

    @Test fun `undecryptable key is regenerated`() {
        val dir = tmp.newFolder()
        val wrapper = XorWrapper()
        val first = AdbKeyStore(dir, wrapper).fingerprint()
        wrapper.broken = true // e.g. Keystore entry wiped after a restore
        val second = AdbKeyStore(dir, wrapper).fingerprint()
        assertNotEquals(first, second)
    }

    @Test fun `reset creates a new identity`() {
        val store = AdbKeyStore(tmp.newFolder(), XorWrapper())
        val before = store.fingerprint()
        store.reset()
        assertNotEquals(before, store.fingerprint())
    }
}
