package dev.firetvonwatch.domain

import dev.firetvonwatch.domain.model.InputDialect
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.protocol.ShellCommandBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandBuilderTest {

    @Test fun `keys are batched into a single keyevent call`() {
        assertEquals(
            "cmd input keyevent 19 19 23",
            ShellCommandBuilder.keys(listOf(RemoteKey.DPAD_UP, RemoteKey.DPAD_UP, RemoteKey.SELECT), InputDialect.CMD_INPUT),
        )
        assertEquals("input keyevent 4", ShellCommandBuilder.keys(listOf(RemoteKey.BACK), InputDialect.INPUT))
    }

    @Test fun `probe output selects dialect`() {
        val modern = ShellCommandBuilder.parseProbe("AFTKRT\n30\n2\n")
        assertEquals("AFTKRT", modern.model)
        assertEquals(30, modern.sdkInt)
        assertEquals(InputDialect.CMD_INPUT, modern.inputDialect)

        val legacy = ShellCommandBuilder.parseProbe("AFTMM\n28\n0\n")
        assertEquals(InputDialect.INPUT, legacy.inputDialect)

        val garbage = ShellCommandBuilder.parseProbe("")
        assertEquals("Fire TV", garbage.model)
        assertEquals(InputDialect.INPUT, garbage.inputDialect)
    }

    @Test fun `text encodes spaces and quotes`() {
        assertEquals(
            "cmd input text 'hello%sworld'",
            ShellCommandBuilder.text("hello world", pressEnter = false, InputDialect.CMD_INPUT),
        )
        assertEquals(
            "input text 'it'\\''s' && input keyevent 66",
            ShellCommandBuilder.text("it's", pressEnter = true, InputDialect.INPUT),
        )
    }

    @Test fun `literal percent is sent separately so it is never read as a space`() {
        assertEquals(
            "cmd input text '50' && cmd input text '%' && cmd input text '%soff'",
            ShellCommandBuilder.text("50% off", pressEnter = false, InputDialect.CMD_INPUT),
        )
        assertEquals(
            "cmd input text 'a' && cmd input text '%' && cmd input text 'sb'",
            ShellCommandBuilder.text("a%sb", pressEnter = false, InputDialect.CMD_INPUT),
        )
    }

    @Test fun `shell metacharacters stay inside single quotes`() {
        val cmd = ShellCommandBuilder.text("\$(reboot);`id`|&", pressEnter = false, InputDialect.CMD_INPUT)
        assertEquals("cmd input text '\$(reboot);`id`|&'", cmd)
    }

    @Test fun `non ascii and oversized text is rejected`() {
        assertFalse(ShellCommandBuilder.isSupportedText("café"))
        assertFalse(ShellCommandBuilder.isSupportedText("line\nbreak"))
        assertFalse(ShellCommandBuilder.isSupportedText(""))
        assertFalse(ShellCommandBuilder.isSupportedText("a".repeat(ShellCommandBuilder.MAX_TEXT_LENGTH + 1)))
        assertTrue(ShellCommandBuilder.isSupportedText("Stranger Things 4"))
    }

    @Test fun `package names are validated before building launch command`() {
        assertTrue(ShellCommandBuilder.isValidPackage("com.netflix.ninja"))
        assertFalse(ShellCommandBuilder.isValidPackage("com.foo; reboot"))
        assertFalse(ShellCommandBuilder.isValidPackage("nodots"))
        val cmd = ShellCommandBuilder.launchApp("com.netflix.ninja")
        assertTrue(cmd.contains("LEANBACK_LAUNCHER com.netflix.ninja"))
        assertTrue("cmd is only used when present", cmd.startsWith("c=; if command -v cmd"))
        assertTrue(cmd.contains("monkey -p com.netflix.ninja"))
    }

    @Test fun `system apps are hidden except well-known streaming apps`() {
        val output = """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
              com.netflix.ninja/.MainActivity
              com.amazon.tv.settings.v2/.Settings
              com.amazon.firebat/com.amazon.firebat.Launcher
            No activities found
              com.netflix.ninja/.MainActivity
              org.xbmc.kodi/.Splash
            ---
            package:org.xbmc.kodi
            package:com.example.notlaunchable
        """.trimIndent()
        val known = setOf("com.amazon.firebat", "com.netflix.ninja")
        assertEquals(
            // Settings (system, unknown) hidden; Kodi (user) kept; non-launchable user package ignored.
            listOf("com.netflix.ninja", "com.amazon.firebat", "org.xbmc.kodi"),
            ShellCommandBuilder.parseLaunchablePackages(output) { it in known },
        )
    }

    @Test fun `old Fire OS pm list output keeps user apps plus known system apps`() {
        val output = """
            package:android
            package:com.amazon.firebat
            package:com.amazon.tv.launcher
            package:com.netflix.ninja
            package:org.xbmc.kodi
            ---
            package:org.xbmc.kodi
        """.trimIndent()
        val known = setOf("com.amazon.firebat", "com.netflix.ninja")
        assertEquals(
            listOf("com.amazon.firebat", "com.netflix.ninja", "org.xbmc.kodi"),
            ShellCommandBuilder.parseLaunchablePackages(output) { it in known },
        )
    }

    @Test fun `commands avoid tools missing on Android 5`() {
        val all = listOf(
            ShellCommandBuilder.PROBE,
            ShellCommandBuilder.launchApp("com.netflix.ninja"),
            ShellCommandBuilder.LIST_LAUNCHABLE_APPS,
        )
        for (c in all) for (tool in listOf("grep", "tail ", "cut ", "head ")) {
            assertFalse("'$tool' in $c", c.contains(tool))
        }
    }
}
