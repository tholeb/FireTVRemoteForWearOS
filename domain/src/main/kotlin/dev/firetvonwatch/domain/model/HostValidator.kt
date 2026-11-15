package dev.firetvonwatch.domain.model

/** Parses user-entered addresses such as `192.168.1.42` or `192.168.1.42:5555`. */
object HostValidator {
    private val ipv4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
    private val hostname = Regex("""^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$""")

    data class Parsed(val host: String, val port: Int)

    fun parse(input: String): Parsed? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        if (parts.size > 2) return null
        val host = parts[0]
        val port = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else DEFAULT_ADB_PORT
        if (port !in 1..65535) return null
        val looksNumeric = host.all { it.isDigit() || it == '.' }
        val valid = if (looksNumeric) ipv4.matches(host) else hostname.matches(host)
        return if (valid) Parsed(host, port) else null
    }
}
