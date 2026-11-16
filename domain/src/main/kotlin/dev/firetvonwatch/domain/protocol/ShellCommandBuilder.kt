package dev.firetvonwatch.domain.protocol

import dev.firetvonwatch.domain.model.InputDialect
import dev.firetvonwatch.domain.model.RemoteKey
import dev.firetvonwatch.domain.model.TvInfo

/**
 * Builds the shell command lines sent to the Fire TV. Everything here is pure and unit-tested.
 *
 * Commands only use shell built-ins and tools present on every Fire OS since 5 (Android 5.1):
 * no grep/tail/cut, and `cmd` (Android 7+) only behind a `command -v` check.
 */
object ShellCommandBuilder {

    const val MAX_TEXT_LENGTH = 500

    /**
     * Run once after connecting. Prints model, SDK level and whether `cmd input` exists
     * (number of help lines that mention `keyevent`).
     */
    const val PROBE =
        "getprop ro.product.model; getprop ro.build.version.sdk; " +
            "case \"\$(cmd input 2>&1)\" in *keyevent*) echo 1 ;; *) echo 0 ;; esac"

    private val packageRegex = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private val componentRegex = Regex("""([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+""")

    fun parseProbe(output: String): TvInfo {
        val lines = output.lines().map { it.trim() }
        val model = lines.getOrNull(0).orEmpty().ifEmpty { "Fire TV" }
        val sdk = lines.getOrNull(1)?.toIntOrNull() ?: 0
        val cmdInputLines = lines.getOrNull(2)?.toIntOrNull() ?: 0
        val dialect = if (cmdInputLines > 0) InputDialect.CMD_INPUT else InputDialect.INPUT
        return TvInfo(model = model, sdkInt = sdk, inputDialect = dialect)
    }

    /** One process for any number of keys: `input keyevent A B C` injects them in order. */
    fun keys(keys: List<RemoteKey>, dialect: InputDialect): String {
        require(keys.isNotEmpty()) { "keys must not be empty" }
        return "${dialect.prefix} keyevent " + keys.joinToString(" ") { it.keyCode.toString() }
    }

    fun isSupportedText(text: String): Boolean =
        text.isNotEmpty() && text.length <= MAX_TEXT_LENGTH && text.all { it in ' '..'~' }

    /**
     * `input text` only accepts ASCII, and it turns the sequence `%s` into a space. So spaces
     * are encoded as `%s` and a literal `%` is sent in a call of its own. Each argument is
     * single-quoted for the TV's shell.
     */
    fun text(text: String, pressEnter: Boolean, dialect: InputDialect): String {
        require(isSupportedText(text)) { "Only printable ASCII up to $MAX_TEXT_LENGTH characters is supported" }
        val commands = mutableListOf<String>()
        val segment = StringBuilder()
        fun flush() {
            if (segment.isNotEmpty()) {
                commands += "${dialect.prefix} text ${shellQuote(segment.toString())}"
                segment.clear()
            }
        }
        for (c in text) {
            when (c) {
                ' ' -> segment.append("%s")
                '%' -> {
                    flush()
                    commands += "${dialect.prefix} text ${shellQuote("%")}"
                }
                else -> segment.append(c)
            }
        }
        flush()
        if (pressEnter) commands += keys(listOf(RemoteKey.ENTER), dialect)
        return commands.joinToString(" && ")
    }

    /** Power state; the output is parsed by [parseAwake]. */
    const val POWER_STATE = "dumpsys power"

    private val wakefulness = Regex("""mWakefulness=(\w+)""")

    /** true = Awake, false = Asleep/Dozing/Dreaming, null = unknown output. */
    fun parseAwake(dumpsysPower: String): Boolean? = when (wakefulness.find(dumpsysPower)?.groupValues?.get(1)) {
        "Awake" -> true
        "Asleep", "Dozing", "Dreaming" -> false
        else -> null
    }

    fun isValidPackage(packageName: String): Boolean = packageRegex.matches(packageName)

    /**
     * Starts an app's TV launcher activity. Falls back to the regular launcher category
     * through `monkey` when the app has no LEANBACK_LAUNCHER activity.
     */
    fun launchApp(packageName: String): String {
        require(isValidPackage(packageName)) { "Invalid package name: $packageName" }
        return "c=; if command -v cmd >/dev/null 2>&1; then " +
            "c=\$(cmd package resolve-activity --brief -a android.intent.action.MAIN " +
            "-c android.intent.category.LEANBACK_LAUNCHER $packageName 2>/dev/null); " +
            "c=\${c##*[[:space:]]}; fi; " + // last line = the component
            "case \"\$c\" in */*) am start -n \"\$c\" ;; " +
            "*) monkey -p $packageName -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 || " +
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1 ;; esac"
    }

    /**
     * Launchable apps, then `---`, then user-installed packages (`pm list packages -3`):
     * - Android 7+: launchable activities from `cmd package query-activities`.
     * - Older: every package (`pm list packages`), as `cmd` doesn't exist there.
     */
    const val LIST_LAUNCHABLE_APPS =
        "if command -v cmd >/dev/null 2>&1; then " +
            "cmd package query-activities --brief -a android.intent.action.MAIN " +
            "-c android.intent.category.LEANBACK_LAUNCHER; " +
            "cmd package query-activities --brief -a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER; " +
            "else pm list packages; fi; echo ---; pm list packages -3"

    private val pmLineRegex = Regex("""^package:([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)\s*$""")

    /**
     * Apps to show: launchable ones that the user installed, plus [isKnown] ones (popular
     * streaming apps that Fire TV preinstalls as system apps). Other system apps are hidden.
     */
    fun parseLaunchablePackages(output: String, isKnown: (String) -> Boolean = { false }): List<String> {
        val lines = output.lines().map { it.trim() }
        val separator = lines.indexOf("---").let { if (it < 0) lines.size else it }
        val launchablePart = lines.subList(0, separator)
        val launchable = if (launchablePart.any { it.startsWith("package:") }) {
            launchablePart.mapNotNull { pmLineRegex.find(it)?.groupValues?.get(1) }
        } else {
            componentRegex.findAll(launchablePart.joinToString("\n")).map { it.groupValues[1] }.toList()
        }
        val userInstalled = lines.drop(separator + 1).mapNotNull { pmLineRegex.find(it)?.groupValues?.get(1) }.toSet()
        return launchable.distinct().filter { it in userInstalled || isKnown(it) }
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
