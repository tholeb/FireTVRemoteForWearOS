package dev.firetvonwatch.testing

import dev.firetvonwatch.domain.NetworkGate

class FakeNetworkGate(var available: Boolean = true) : NetworkGate {
    var held = 0
        private set
    var acquireCount = 0
        private set

    override suspend fun acquire(timeoutMs: Long): Boolean {
        acquireCount++
        held++
        return available
    }

    override fun release() {
        if (held > 0) held--
    }
}
