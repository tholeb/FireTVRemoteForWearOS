package dev.firetvonwatch.wear

import android.content.Context
import dev.firetvonwatch.data.adb.AdbKeyStore
import dev.firetvonwatch.data.adb.DadbShellTransportFactory
import dev.firetvonwatch.data.adb.KeystoreSecretWrapper
import dev.firetvonwatch.data.discovery.NsdDeviceDiscovery
import dev.firetvonwatch.data.discovery.SubnetScanner
import dev.firetvonwatch.data.network.WifiNetworkGate
import dev.firetvonwatch.data.repository.DataStoreDeviceRepository
import dev.firetvonwatch.data.repository.DataStoreSettingsRepository
import dev.firetvonwatch.data.repository.FileAppDetailsCache
import dev.firetvonwatch.data.repository.PreferenceStore
import dev.firetvonwatch.domain.CompositeDeviceDiscovery
import dev.firetvonwatch.domain.DeviceDiscovery
import dev.firetvonwatch.domain.FireTvConnection
import dev.firetvonwatch.domain.protocol.InjectorPayload
import dev.firetvonwatch.domain.protocol.ShellFireTvConnection
import dev.firetvonwatch.domain.repository.DeviceRepository
import dev.firetvonwatch.domain.repository.SettingsRepository
import dev.firetvonwatch.domain.session.RemoteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** What the UI needs. Tests provide fakes through [AppContainer.Companion.forTesting]. */
interface AppDependencies {
    val session: RemoteSession
    val deviceRepository: DeviceRepository
    val settingsRepository: SettingsRepository
    val discovery: DeviceDiscovery
    fun keyFingerprint(): String
    fun resetKey()
}

/** Hand-written dependency injection: one instance per process, created by [RemoteApplication]. */
class AppContainer(context: Context) : AppDependencies {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val preferences = PreferenceStore.create(appContext)
    override val deviceRepository: DeviceRepository = DataStoreDeviceRepository(preferences)
    override val settingsRepository: SettingsRepository = DataStoreSettingsRepository(preferences)

    private val keyStore = AdbKeyStore(appContext.noBackupFilesDir, KeystoreSecretWrapper())
    private val networkGate = WifiNetworkGate(appContext)

    /** On-TV key injector shipped as an asset (built from wear/src/injector by Gradle). */
    private val injectorPayload: InjectorPayload? by lazy {
        runCatching { InjectorPayload(appContext.assets.open("injector.dex").use { it.readBytes() }) }.getOrNull()
    }

    private val connection: FireTvConnection = ShellFireTvConnection(
        factory = DadbShellTransportFactory(keyPair = keyStore::getOrCreate),
        ioDispatcher = Dispatchers.IO,
        injectorPayload = { injectorPayload },
    )

    override val session = RemoteSession(
        connection, networkGate, deviceRepository, settingsRepository, appScope,
        appDetailsCache = FileAppDetailsCache(java.io.File(appContext.cacheDir, "app-details")),
    )

    override val discovery: DeviceDiscovery = CompositeDeviceDiscovery(
        network = networkGate,
        sources = listOf(NsdDeviceDiscovery(appContext), SubnetScanner(networkGate::currentIpv4)),
    )

    override fun keyFingerprint(): String = keyStore.fingerprint()

    override fun resetKey() = keyStore.reset()
}
