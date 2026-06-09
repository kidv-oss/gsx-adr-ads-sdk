package com.compose.gsxgoogleadscompose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compose.gsxgoogleadscompose.common.PremiumStatusText
import com.gsx.googleadcompose.GlobalVariables
import androidx.compose.ui.res.stringArrayResource
import com.compose.gsxgoogleadscompose.R
import com.gsx.googleadcompose.GoogleAds.AdmOpen
import com.gsx.googleadcompose.GoogleAds.nativead.AdmNativeSequenceState
import com.gsx.googleadcompose.GoogleConsent.AdmUMP
import com.gsx.googleadcompose.GoogleIAP.BillingClient
import com.gsx.googleadcompose.GoogleIAP.BillingEvents
import kotlinx.coroutines.delay

/**
 * Chờ fetch giá xong (hoặc timeout) rồi mới điều hướng tiếp, để màn Premium có sẵn giá ngay.
 */
@Composable
fun SplashScreen(
    onDone: () -> Unit,
    timeoutMs: Long = 6000L,
) {
    var navigated by remember { mutableStateOf(false) }
    // BillingEvents có thể bắn từ thread billing -> set cờ (snapshot thread-safe), điều hướng ở
    // LaunchedEffect dưới (chạy trên main) để tránh "navigate phải trên main thread".
    var requestGo by remember { mutableStateOf(false) }
    val go: () -> Unit = { requestGo = true }

    // Open ad splash = instance RIÊNG (tách khỏi resume), tự load+show, tự destroy khi rời splash.
    val openSplash = AdmOpen()
    val openIds = stringArrayResource(R.array.app_open_ad_units_custom).toList()   // đọc resource ở scope composable

    LaunchedEffect(requestGo) {
        if (requestGo && !navigated) {
            navigated = true
            openSplash.showFromSplash(customIds = openIds) { onDone() }
        }
    }
    BillingEvents(onProductsFetched = { go() })
    // UMP consent + init MobileAds 1 lần. onAdsInitialized bắn khi ads SẴN -> preload (khỏi poll).
    val ump = AdmUMP()
    LaunchedEffect(Unit) {
        ump.initUMP(
            onAdsInitialized = {     AdmNativeSequenceState.init(listOf(0, 1, 2, 3,4))
            },
            gatherConsentFinished = {},
        )
    }

    LaunchedEffect(Unit) {
        if (BillingClient.isProductsFetched) {
            go()
            return@LaunchedEffect
        }
        delay(timeoutMs)
        go()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF6A2C91), Color(0xFF2A0E45), Color(0xFF120726))
                )
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "GSX Compose Ads",
            color = Color(0xFF52DE11),
            fontSize = 36.sp,
            fontWeight = FontWeight.W700,
        )
        PremiumStatusText()
        CircularProgressIndicator(
            modifier = Modifier.padding(top = 24.dp),
            color = Color.White,
        )
    }
}
