package dev.firetvonwatch.domain.model

sealed interface RemoteCommand {
    data class Key(val key: RemoteKey) : RemoteCommand
    data class Text(val text: String, val pressEnter: Boolean = false) : RemoteCommand
    data class LaunchApp(val packageName: String) : RemoteCommand
}
