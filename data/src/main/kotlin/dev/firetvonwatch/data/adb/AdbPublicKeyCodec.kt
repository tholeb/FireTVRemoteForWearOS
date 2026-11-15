package dev.firetvonwatch.data.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Encodes an RSA public key in the format adbd expects in an AUTH(RSAPUBLICKEY) packet
 * (AOSP `android_pubkey_encode`): base64 of { len, n0inv, n, rr, e } in little-endian,
 * then a space, a key name and a NUL byte.
 */
object AdbPublicKeyCodec {
    private const val KEY_BITS = 2048
    private const val WORDS = KEY_BITS / 32
    private const val BYTES = KEY_BITS / 8

    fun encode(key: RSAPublicKey, name: String): ByteArray {
        val n = key.modulus
        require(n.bitLength() == KEY_BITS) { "Only $KEY_BITS-bit keys are supported" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)

        val buf = ByteBuffer.allocate(4 + 4 + BYTES + BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WORDS)
        buf.putInt(n0inv.toInt())
        buf.put(littleEndian(n))
        buf.put(littleEndian(rr))
        buf.putInt(key.publicExponent.toInt())

        val text = Base64.getEncoder().encodeToString(buf.array()) + " " + name
        return text.toByteArray(Charsets.UTF_8) + 0
    }

    /**
     * MD5 fingerprint of the key blob, as the Fire TV shows it in the
     * "Allow USB debugging?" dialog, so the user can check it's this watch.
     */
    fun fingerprint(encoded: ByteArray): String {
        val text = encoded.toString(Charsets.UTF_8).trimEnd('\u0000')
        val blob = Base64.getDecoder().decode(text.substringBefore(' '))
        return MessageDigest.getInstance("MD5").digest(blob).joinToString(":") { "%02X".format(it) }
    }

    private fun littleEndian(value: BigInteger): ByteArray {
        val bigEndian = value.toByteArray().let { if (it.size > BYTES) it.copyOfRange(it.size - BYTES, it.size) else it }
        val out = ByteArray(BYTES)
        for (i in bigEndian.indices) out[i] = bigEndian[bigEndian.size - 1 - i]
        return out
    }
}
