package com.gsx.googleadcompose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gsx.googleadcompose.GoogleConsent.findActivity

/**
 * Ẩn status bar + nav bar (immersive) trong khi composable này còn hiển thị; tự hiện lại khi rời.
 * Vuốt từ mép để hiện tạm thời. Gọi trong màn cần full-screen (vd PremiumScreen).
 *
 * ```
 * @Composable fun PremiumScreen() {
 *     HideSystemBars()
 *     ...
 * }
 * ```
 */
@Composable
fun HideSystemBars() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
