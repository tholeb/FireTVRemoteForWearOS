package dev.firetvonwatch.data.adb

import dadb.AdbStream
import dev.firetvonwatch.domain.protocol.InteractiveShell

/**
 * Long-running process over the legacy `shell:` service (Android < 7). That service runs
 * commands in a pty, so every line we write is echoed back before the process answers. The
 * echoes are skipped here, so callers see only the process's own output.
 */
class LegacyInteractiveShell(private val stream: AdbStream) : InteractiveShell {
    private val pendingEchoes = ArrayDeque<String>()

    override fun writeLine(line: String) {
        synchronized(pendingEchoes) { pendingEchoes.addLast(line) }
        stream.sink.writeUtf8(line + "\n")
        stream.sink.flush()
    }

    override fun readLine(): String? {
        while (true) {
            val line = (stream.source.readUtf8Line() ?: return null).trimEnd('\r')
            val isEcho = synchronized(pendingEchoes) {
                if (pendingEchoes.firstOrNull() == line) {
                    pendingEchoes.removeFirst()
                    true
                } else {
                    false
                }
            }
            if (!isEcho) return line
        }
    }

    override fun close() {
        runCatching { stream.close() }
    }
}
