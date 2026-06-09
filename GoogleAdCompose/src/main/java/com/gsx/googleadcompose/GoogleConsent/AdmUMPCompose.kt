package com.gsx.googleadcompose.GoogleConsent

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Lấy [_AdmUMP] gắn với Activity host, tự resolve an toàn từ context Compose.
 *
 * Không cần tự cast `LocalContext.current as Activity` — cast đó có thể crash ClassCastException vì
 * context Compose thường là ContextWrapper chứ không phải Activity.
 *
 * ```
 * val ump = AdmUMP()
 * LaunchedEffect(Unit) {
 *     ump.initUMP(gatherConsentFinished = { /* ready -> vào app */ })
 * }
 * ```
 */
@Composable
fun AdmUMP(): _AdmUMP {
    val context = LocalContext.current
    val activity = remember(context) {
        context.findActivity()
            ?: error("AdmUMP() phải được gọi từ composable nằm trong một Activity")
    }
    return remember(activity) { _AdmUMP(activity) }
}

/** Lấy Activity từ Context (có thể bị wrap). Trả null nếu không có. Dùng được cả trong app. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
