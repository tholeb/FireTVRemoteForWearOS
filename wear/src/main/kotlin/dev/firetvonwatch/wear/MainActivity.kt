package dev.firetvonwatch.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.firetvonwatch.wear.ui.FireTvApp
import dev.firetvonwatch.wear.ui.theme.FireTvTheme

class MainActivity : ComponentActivity() {
    private val container get() = appContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FireTvTheme { FireTvApp(container) }
        }
    }

    override fun onStart() {
        super.onStart()
        container.session.setForeground(true)
    }

    override fun onStop() {
        container.session.setForeground(false)
        super.onStop()
    }
}
