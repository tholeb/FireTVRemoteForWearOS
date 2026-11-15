package dev.firetvonwatch.data.repository

import dev.firetvonwatch.domain.model.AppDetails
import dev.firetvonwatch.domain.repository.AppDetailsCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * App labels and icons on disk (`<package>.label` / `<package>.png`) with an in-memory layer.
 * Lives in the cache directory: the system may clear it, and it's just fetched again.
 */
class FileAppDetailsCache(private val directory: File) : AppDetailsCache {
    private val memory = ConcurrentHashMap<String, AppDetails>()
    private val safeName = Regex("^[A-Za-z0-9_.]+$")

    override fun get(packageName: String): AppDetails? {
        memory[packageName]?.let { return it }
        if (!safeName.matches(packageName)) return null
        val labelFile = File(directory, "$packageName.label")
        if (!labelFile.exists()) return null
        val icon = File(directory, "$packageName.png").takeIf { it.exists() }?.readBytes()
        return AppDetails(packageName, labelFile.readText(), icon).also { memory[packageName] = it }
    }

    override fun put(details: AppDetails) {
        memory[details.packageName] = details
        if (!safeName.matches(details.packageName)) return
        runCatching {
            directory.mkdirs()
            details.icon?.let { File(directory, "${details.packageName}.png").writeBytes(it) }
                ?: File(directory, "${details.packageName}.png").delete()
            File(directory, "${details.packageName}.label").writeText(details.label)
        }
    }

    override fun clear() {
        memory.clear()
        directory.listFiles()?.forEach { it.delete() }
    }
}
