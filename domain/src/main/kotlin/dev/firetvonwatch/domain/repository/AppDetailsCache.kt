package dev.firetvonwatch.domain.repository

import dev.firetvonwatch.domain.model.AppDetails

/** Keeps app labels/icons on the watch so the Apps page doesn't ask the TV every time. */
interface AppDetailsCache {
    fun get(packageName: String): AppDetails?
    fun put(details: AppDetails)
    fun clear()
}

class InMemoryAppDetailsCache : AppDetailsCache {
    private val map = java.util.concurrent.ConcurrentHashMap<String, AppDetails>()
    override fun get(packageName: String) = map[packageName]
    override fun put(details: AppDetails) { map[details.packageName] = details }
    override fun clear() = map.clear()
}
