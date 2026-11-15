package dev.firetvonwatch.data

import dadb.AdbKeyPair
import dev.firetvonwatch.data.adb.AdbPublicKeyCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class AdbPublicKeyCodecTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Our encoder must produce byte-identical output to dadb's (= AOSP adb's) key format. */
    @Test fun `matches dadb reference encoding`() {
        val priv = tmp.newFile("adbkey"); val pub = tmp.newFile("adbkey.pub")
        AdbKeyPair.generate(priv, pub)

        val pem = priv.readText().lines().filterNot { it.startsWith("-----") }.joinToString("")
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(pem))) as RSAPrivateCrtKey
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey

        val ours = AdbPublicKeyCodec.encode(publicKey, "unknown@unknown")
        val reference = pub.readBytes() + 0
        assertArrayEquals(reference, ours)
    }

    @Test fun `fingerprint is md5 of the key blob`() {
        val priv = tmp.newFile("k"); val pub = tmp.newFile("k.pub")
        AdbKeyPair.generate(priv, pub)
        val fp = AdbPublicKeyCodec.fingerprint(pub.readBytes() + 0)
        assertEquals(16 * 3 - 1, fp.length)
        assertTrue(fp.matches(Regex("([0-9A-F]{2}:){15}[0-9A-F]{2}")))
    }
}
