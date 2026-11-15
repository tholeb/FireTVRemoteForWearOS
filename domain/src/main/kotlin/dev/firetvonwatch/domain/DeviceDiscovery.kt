package dev.firetvonwatch.domain

import dev.firetvonwatch.domain.model.DiscoveredDevice
import kotlinx.coroutines.flow.Flow

fun interface DeviceDiscovery {
    /** Cold flow that emits devices as they are found and completes when the scan ends. */
    fun discover(): Flow<DiscoveredDevice>
}
