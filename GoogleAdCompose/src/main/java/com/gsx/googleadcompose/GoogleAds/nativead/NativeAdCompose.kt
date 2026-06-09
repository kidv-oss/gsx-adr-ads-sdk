package com.gsx.googleadcompose.GoogleAds.nativead

import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.libraries.ads.mobile.sdk.common.AdChoicesView
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/**
 * Compose wrapper cho NativeAdView (copy nguyên từ Google git `NativeComposeUtility`).
 * Mỗi asset (headline/icon/media/cta...) bọc 1 wrapper để đăng ký view với SDK -> track
 * click/impression đúng. Dùng trong layout native ([NativeAdTestLayout]...).
 */

/** CompositionLocal cấp [NativeAd] cho asset con (vd [NativeAdMediaView]). */
internal val LocalNativeAd = staticCompositionLocalOf<NativeAd?> { null }

/** CompositionLocal cấp [NativeAdView] cho asset con (vd [NativeAdHeadlineView]). */
internal val LocalNativeAdView = staticCompositionLocalOf<NativeAdView?> { null }

/** CompositionLocal cấp hàm đăng ký MediaView (cache để truyền vào registerNativeAd). */
internal val LocalMediaViewRegister = staticCompositionLocalOf<(MediaView?) -> Unit> { {} }

@Composable
fun NativeAdView(
    nativeAd: NativeAd,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val nativeAdViewRef = remember { mutableStateOf<NativeAdView?>(null) }
    val mediaViewRef = remember { mutableStateOf<MediaView?>(null) }

    AndroidView(
        factory = { context ->
            val composeView =
                ComposeView(context).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,   // fill NativeAdView
                        )
                }
            // NativeAdView WRAP_CONTENT -> size theo Modifier Compose: fillMaxSize -> fill;
            // fillMaxWidth -> wrap chiều cao theo content layout (không chiếm hết màn).
            NativeAdView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                addView(composeView)
                nativeAdViewRef.value = this
            }
        },
        modifier = modifier,
        update = { view ->
            val composeView = view.getChildAt(0) as? ComposeView
            composeView?.setContent {
                val registerMediaView: (MediaView?) -> Unit =
                    remember { { mv -> mediaViewRef.value = mv } }
                CompositionLocalProvider(
                    LocalNativeAdView provides view,
                    LocalNativeAd provides nativeAd,
                    LocalMediaViewRegister provides registerMediaView,
                ) {
                    content()
                }
            }
        },
    )
    val currentNativeAd by rememberUpdatedState(nativeAd)
    val currentNativeAdView = nativeAdViewRef.value
    val currentMediaView = mediaViewRef.value

    DisposableEffect(currentNativeAd, currentNativeAdView, currentMediaView) {
        currentNativeAdView?.register(currentNativeAd, currentMediaView)
        onDispose {}
    }
}

@Composable
fun NativeAdAdvertiserView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view -> nativeAdView.advertiserView = view; view.setContent(content) },
    )
}

@Composable
fun NativeAdBodyView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view -> nativeAdView.bodyView = view; view.setContent(content) },
    )
}

@Composable
fun NativeAdCallToActionView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view -> nativeAdView.callToActionView = view; view.setContent(content) },
    )
}

@Composable
fun NativeAdChoicesView(modifier: Modifier = Modifier) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context ->
            AdChoicesView(context).apply { minimumWidth = 15; minimumHeight = 15 }
        },
        modifier = modifier,
        update = { view -> nativeAdView.adChoicesView = view },
    )
}

@Composable
fun NativeAdHeadlineView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view -> nativeAdView.headlineView = view; view.setContent(content) },
    )
}

@Composable
fun NativeAdIconView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view -> nativeAdView.iconView = view; view.setContent(content) },
    )
}

@Composable
fun NativeAdMediaView(modifier: Modifier = Modifier, scaleType: ImageView.ScaleType? = null) {
    val registerMediaView = LocalMediaViewRegister.current
    AndroidView(
        factory = { context -> MediaView(context) },
        update = { view ->
            registerMediaView(view)
            scaleType?.let { type -> view.imageScaleType = type }
        },
        modifier = modifier,
    )
    DisposableEffect(Unit) { onDispose { registerMediaView(null) } }
}

@Composable
fun NativeAdPriceView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view -> nativeAdView.priceView = view; view.setContent(content) },
    )
}

@Composable
fun NativeAdStarRatingView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view -> nativeAdView.starRatingView = view; view.setContent(content) },
    )
}

@Composable
fun NativeAdStoreView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view -> nativeAdView.storeView = view; view.setContent(content) },
    )
}

@Composable
fun NativeAdAttribution(
    modifier: Modifier = Modifier,
    text: String = "Ad",
    shape: Shape = MaterialTheme.shapes.extraSmall,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    padding: PaddingValues = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
) {
    Box(modifier = modifier.background(color = containerColor, shape = shape).padding(padding)) {
        Text(text = text, color = contentColor, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun NativeAdButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
    ) {
        Text(text = text)
    }
}

private fun NativeAdView.register(nativeAd: NativeAd, mediaView: MediaView?) {
    if (mediaView != null) {
        // Post để chắc MediaView đã đo xong (tránh validator 120x120 fail khi view còn 0x0).
        mediaView.post { this.registerNativeAd(nativeAd, mediaView) }
    } else {
        this.registerNativeAd(nativeAd, null)
    }
}
