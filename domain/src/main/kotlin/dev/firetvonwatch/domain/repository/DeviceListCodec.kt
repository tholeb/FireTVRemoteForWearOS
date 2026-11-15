package dev.firetvonwatch.domain.repository

import dev.firetvonwatch.domain.model.FireTvDevice

/**
 * Turns the device list into a single string for preference storage: one device per line,
 * tab-separated fields, with backslash, tab and newline escaped.
 */
object DeviceListCodec {
    fun encode(devices: List<FireTvDevice>): String = devices.joinToString("\n") { d ->
        listOf(d.id, d.name, d.host, d.port.toString(), if (d.authorized) "1" else "0")
            .joinToString("\t") { escape(it) }
    }

    fun decode(raw: String?): List<FireTvDevice> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val fields = line.split('\t').map { unescape(it) }
            if (fields.size < 5) return@mapNotNull null
            val port = fields[3].toIntOrNull() ?: return@mapNotNull null
            FireTvDevice(id = fields[0], name = fields[1], host = fields[2], port = port, authorized = fields[4] == "1")
        }
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            else -> append(c)
        }
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c); i++
            }
        }
    }
}
