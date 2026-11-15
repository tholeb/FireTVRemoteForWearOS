package dev.firetvonwatch.domain.protocol

import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.AppDetails
import java.security.MessageDigest
import java.util.Base64

/**
 * The on-TV key injector (see wear/src/injector): a small dex that the watch pushes to the TV
 * and keeps running with `app_process`, so each key costs a line on stdin instead of a new JVM.
 */
class InjectorPayload(val bytes: ByteArray) {
    /** Content hash. A changed injector gets a new file name, so stale copies are never reused. */
    val id: String = MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
    val remotePath: String = "${InjectorProtocol.REMOTE_DIR}/injector-$id.dex"
}

object InjectorProtocol {
    const val MAIN_CLASS = "dev.firetvonwatch.injector.Injector"
    const val READY = "READY"
    const val OK = "OK"

    /**
     * Private (0700, shell-owned) working directory. It holds the dex and ART's dalvik-cache
     * (via ANDROID_DATA), so no other user on the TV can read or replace either.
     */
    const val REMOTE_DIR = "/data/local/tmp/firetvonwatch"
    private const val PREPARE_DIR = "mkdir -p $REMOTE_DIR/dalvik-cache && chmod 700 $REMOTE_DIR"

    /**
     * Makes the dex read-only (adb push may leave it world-writable) and runs it with its ART
     * cache inside the private directory.
     */
    fun startCommand(payload: InjectorPayload): String =
        "$PREPARE_DIR && chmod 644 ${payload.remotePath} && " +
            "ANDROID_DATA=$REMOTE_DIR CLASSPATH=${payload.remotePath} exec app_process /system/bin $MAIN_CLASS"

    /**
     * Prints "present" only if the file on the TV has our exact content (SHA-256 prefix);
     * anything else (missing, truncated, modified, or no `sha256sum` before Android 6) gets
     * pushed again.
     */
    fun presenceCheck(payload: InjectorPayload): String =
        "$PREPARE_DIR; case \"\$(sha256sum ${payload.remotePath} 2>/dev/null)\" in " +
            "${payload.id}*) echo present ;; *) echo missing ;; esac"

    /** Deletes injector files of other versions (left over from app updates). */
    fun cleanupOthers(payload: InjectorPayload): String =
        "for f in $REMOTE_DIR/injector-*.dex; do [ \"\$f\" = \"${payload.remotePath}\" ] || rm -f \"\$f\"; done"

    fun keys(keys: List<RemoteKey>): String = "K " + keys.joinToString(" ") { it.keyCode.toString() }

    fun isReady(line: String?): Boolean = line?.startsWith(READY) == true

    fun appInfo(packageName: String): String = "I $packageName"

    /** Parses "I <b64 label> <b64 png | ->"; null for ERR or malformed replies. */
    fun parseAppInfo(packageName: String, line: String?): AppDetails? {
        val parts = line?.trim()?.split(' ') ?: return null
        if (parts.size != 3 || parts[0] != "I") return null
        val decoder = Base64.getDecoder()
        return try {
            val label = String(decoder.decode(parts[1]), Charsets.UTF_8).ifBlank { packageName }
            val icon = if (parts[2] == "-") null else decoder.decode(parts[2])
            AppDetails(packageName, label, icon)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
