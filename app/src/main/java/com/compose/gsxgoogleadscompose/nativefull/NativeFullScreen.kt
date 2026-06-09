package com.compose.gsxgoogleadscompose.nativefull

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.gsx.googleadcompose.GoogleAds.nativead.AdmNative
import com.gsx.googleadcompose.GoogleAds.nativead.AdmNativeAd
import com.gsx.googleadcompose.GoogleAds.nativead.NativeLayout

/** Màn native full-screen: preload native -> [AdmNative] layout [NativeLayout.FULL] phủ màn. */
@Composable
fun NativeFullScreen(onClose: () -> Unit = {}) {
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }
    LaunchedEffect(Unit) { AdmNativeAd.preload { nativeAd = it } }
    DisposableEffect(Unit) { onDispose { nativeAd?.destroy() } }

    Box(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        AdmNative(
            modifier = Modifier.fillMaxSize(),
            layout = NativeLayout.FULL,
            nativeAd = nativeAd,
        ) {
            ctaColor = Color(0xFFFF6D00)        // nút install cam
            ctaTextColor = Color.White
        }
    }
}
