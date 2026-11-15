package dev.firetvonwatch.data.adb

import dadb.AdbKeyPair
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * The watch's ADB identity: an RSA-2048 key pair. The private key is stored encrypted with
 * [wrapper] and only decrypted in memory. Losing it (e.g. if the Keystore was wiped) just
 * creates a new key, and the TV asks for authorization again.
 */
class AdbKeyStore(
    private val directory: File,
    private val wrapper: SecretWrapper,
    private val keyName: String = "FireTVOnWatch@wear",
) {
    private val privateFile get() = File(directory, "adbkey.enc")
    private val publicFile get() = File(directory, "adbkey.pub")

    @Volatile private var cached: AdbKeyPair? = null
    @Volatile private var cachedPublic: ByteArray? = null

    @Synchronized
    fun getOrCreate(): AdbKeyPair {
        cached?.let { return it }
        val pair = load() ?: generate()
        cached = pair
        return pair
    }

    /** Fingerprint the TV shows in its authorization dialog. */
    @Synchronized
    fun fingerprint(): String {
        getOrCreate()
        return AdbPublicKeyCodec.fingerprint(cachedPublic!!)
    }

    /** Deletes the identity. Every TV will need to authorize the watch again. */
    @Synchronized
    fun reset() {
        privateFile.delete()
        publicFile.delete()
        cached = null
        cachedPublic = null
    }

    private fun load(): AdbKeyPair? {
        if (!privateFile.exists() || !publicFile.exists()) return null
        return runCatching {
            val der = wrapper.unwrap(privateFile.readBytes())
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
            val publicBytes = publicFile.readBytes()
            cachedPublic = publicBytes
            AdbKeyPair(privateKey, publicBytes)
        }.getOrNull()
    }

    private fun generate(): AdbKeyPair {
        directory.mkdirs()
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keyPair = generator.generateKeyPair()
        val publicBytes = AdbPublicKeyCodec.encode(keyPair.public as RSAPublicKey, keyName)
        writeAtomically(privateFile, wrapper.wrap(keyPair.private.encoded))
        writeAtomically(publicFile, publicBytes)
        cachedPublic = publicBytes
        return AdbKeyPair(keyPair.private, publicBytes)
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not write ${target.name}" }
        }
    }
}
