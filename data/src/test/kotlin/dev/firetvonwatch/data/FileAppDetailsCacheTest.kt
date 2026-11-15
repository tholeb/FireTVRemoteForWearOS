package dev.firetvonwatch.data

import dev.firetvonwatch.data.repository.FileAppDetailsCache
import dev.firetvonwatch.domain.model.AppDetails
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileAppDetailsCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `survives a restart and handles apps without icon`() {
        val dir = tmp.newFolder()
        FileAppDetailsCache(dir).apply {
            put(AppDetails("com.netflix.ninja", "Netflix", byteArrayOf(1, 2, 3)))
            put(AppDetails("org.example.noicon", "Sans icône", null))
        }
        val reloaded = FileAppDetailsCache(dir)
        val netflix = reloaded.get("com.netflix.ninja")!!
        assertEquals("Netflix", netflix.label)
        assertArrayEquals(byteArrayOf(1, 2, 3), netflix.icon)
        assertEquals("Sans icône", reloaded.get("org.example.noicon")!!.label)
        assertNull(reloaded.get("org.example.noicon")!!.icon)
        assertNull(reloaded.get("../etc/passwd"))
        reloaded.clear()
        assertNull(FileAppDetailsCache(dir).get("com.netflix.ninja"))
    }
}
