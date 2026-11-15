package dev.firetvonwatch.wear

import android.app.Application

class RemoteApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as RemoteApplication).container
