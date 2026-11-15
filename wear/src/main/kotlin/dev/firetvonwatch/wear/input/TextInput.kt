package dev.firetvonwatch.wear.input

import android.app.Activity
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import android.app.RemoteInput
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender

private const val RESULT_KEY = "text"

/**
 * Opens the system text-input UI (keyboard, voice, handwriting, depending on the watch)
 * and returns the text through [onResult].
 */
@Composable
fun rememberTextInputLauncher(label: String, onResult: (String) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(RESULT_KEY)?.toString()
        if (!text.isNullOrBlank()) callback.value(text)
    }
    return {
        val input = RemoteInput.Builder(RESULT_KEY)
            .setLabel(label)
            .wearableExtender {
                setEmojisAllowed(false)
                setInputActionType(EditorInfo.IME_ACTION_SEND)
            }
            .build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        launcher.launch(intent)
    }
}
