package dev.firetvonwatch.domain.model

const val DEFAULT_ADB_PORT = 5555

/** A Fire TV the user has added. */
data class FireTvDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = DEFAULT_ADB_PORT,
    /** True once an ADB connection was accepted at least once (the TV has our key). */
    val authorized: Boolean = false,
) {
    val address: String get() = if (port == DEFAULT_ADB_PORT) host else "$host:$port"

    companion object {
        fun idFor(host: String, port: Int): String = "$host:$port"
    }
}

/** A device found on the LAN that may not have been added yet. */
data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int = DEFAULT_ADB_PORT,
    val source: Source,
) {
    enum class Source { MDNS, SUBNET_SCAN, MANUAL }

    fun toDevice(): FireTvDevice = FireTvDevice(id = FireTvDevice.idFor(host, port), name = name, host = host, port = port)
}
