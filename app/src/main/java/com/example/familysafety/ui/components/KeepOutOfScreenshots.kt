package com.example.familysafety.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Marks the window `FLAG_SECURE` while the calling screen is shown: screenshots and screen
 * recordings come out black, and the recents thumbnail is blank. For screens that show or
 * take the recovery phrase.
 *
 * Covers the screen's own window only. A dialog has its own; give it
 * `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` instead.
 */
@Composable
fun KeepOutOfScreenshots() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
