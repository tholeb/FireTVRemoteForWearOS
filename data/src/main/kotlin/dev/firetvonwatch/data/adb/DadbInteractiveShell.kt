package dev.firetvonwatch.data.adb

import dadb.AdbShellPacket
import dadb.AdbShellStream
import dev.firetvonwatch.domain.protocol.InteractiveShell

/**
 * Line-based view of a dadb shell-v2 stream. stdout and stderr are merged, and an exit packet
 * or a closed stream ends the input (readLine returns null).
 */
class DadbInteractiveShell(private val stream: AdbShellStream) : InteractiveShell {
    private val buffer = StringBuilder()
    private var ended = false

    @Synchronized
    override fun writeLine(line: String) {
        stream.write(line + "\n")
    }

    override fun readLine(): String? {
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline >= 0) {
                val line = buffer.substring(0, newline).trimEnd('\r')
                buffer.delete(0, newline + 1)
                return line
            }
            if (ended) return buffer.takeIf { it.isNotEmpty() }?.toString()?.also { buffer.clear() }
            val packet = try {
                stream.read()
            } catch (e: Exception) {
                ended = true
                continue
            }
            when (packet) {
                is AdbShellPacket.Exit -> ended = true
                else -> buffer.append(String(packet.payload, Charsets.UTF_8))
            }
        }
    }

    override fun close() {
        runCatching { stream.close() }
    }
}
