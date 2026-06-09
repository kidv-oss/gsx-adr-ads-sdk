package com.gsx.googleadcompose.GoogleAds

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Shimmer kiểu Facebook — gradient sáng quét ngang lặp vô hạn, đắp lên placeholder lúc ad đang load.
 * Dùng chung cho [AdmBanner] + [AdmNative]. Modifier reactive theo size (đo qua onGloballyPositioned).
 */
@Composable
private fun Modifier.shimmer(
    shape: Shape = RoundedCornerShape(6.dp),
    base: Color = Color(0xFFBDBDBD),
    highlight: Color = Color(0xFFE8E8E8),
): Modifier {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "x",
    )
    val w = size.width.toFloat().coerceAtLeast(1f)
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x * w - w, 0f),
        end = Offset(x * w, 0f),
    )
    return this
        .clip(shape)
        .background(brush)
        .onGloballyPositioned { size = it.size }
}

/** Ô shimmer 1 mảng (placeholder block). */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    Box(modifier.shimmer(shape))
}

/** Skeleton banner: 1 mảng chữ nhật cao [heightDp]. */
@Composable
fun AdShimmerBanner(modifier: Modifier = Modifier, heightDp: Int) {
    ShimmerBox(
        modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(8.dp),
    )
}
