package com.compose.gsxgoogleadscompose.nav

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gsx.googleadcompose.GoogleAds.AdmBanner
import com.gsx.googleadcompose.GoogleAds.AdmBannerCollapsible
import com.gsx.googleadcompose.GoogleAds.AdmBannerSize
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.gsx.googleadcompose.GoogleAds.nativead.AdmNative
import com.gsx.googleadcompose.GoogleAds.nativead.AdmNativeAd
import com.gsx.googleadcompose.GoogleAds.nativead.NativeLayout
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.compose.gsxgoogleadscompose.SplashScreen
import com.compose.gsxgoogleadscompose.common.PremiumStatusText
import com.compose.gsxgoogleadscompose.premium.PremiumScreen
import com.compose.gsxgoogleadscompose.interstitial.InterstitialScreen
import com.compose.gsxgoogleadscompose.open.OpenScreen
import com.compose.gsxgoogleadscompose.nativefull.NativeFullScreen
import com.compose.gsxgoogleadscompose.onboarding.OnboardingScreen
import com.compose.gsxgoogleadscompose.rewardinterstitial.RewardInterstitialScreen
import com.compose.gsxgoogleadscompose.reward.RewardScreen
import com.gsx.googleadcompose.utils.PreferencesManager

private object Route {
    const val SPLASH = "splash"
    const val MAIN = "main"
    const val REWARD = "reward"
    const val INTER = "inter"
    const val REWARD_INTER = "rewardinter"
    const val OPEN = "open"
    const val NATIVE_FULL = "nativefull"
    const val ONBOARDING = "onboarding"
    const val PREMIUM = "premium?fromSplash={fromSplash}"
    fun premium(fromSplash: Boolean) = "premium?fromSplash=$fromSplash"
}

/** Navigation Compose: 1 activity, các màn là composable. */
@Composable
fun AppNav() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Route.SPLASH) {

        composable(Route.SPLASH) {
            SplashScreen(onDone = {
                val dest = if (PreferencesManager.getInstance().isShowOnBoard())
                    Route.ONBOARDING else Route.MAIN
                nav.navigate(dest) { popUpTo(Route.SPLASH) { inclusive = true } }
            })
        }

        composable(
            route = Route.PREMIUM,
            arguments = listOf(navArgument("fromSplash") {
                type = NavType.BoolType; defaultValue = false
            }),
        ) { entry ->
            val fromSplash = entry.arguments?.getBoolean("fromSplash") ?: false
            Box(modifier = Modifier.fillMaxSize()) {
                PremiumScreen(onClose = {
                    if (fromSplash) {
                        nav.navigate(Route.MAIN) { popUpTo(Route.PREMIUM) { inclusive = true } }
                    } else {
                        nav.popBackStack()
                    }
                })
                PremiumStatusText(modifier = Modifier.align(Alignment.BottomCenter))
            }
        }

        composable(Route.MAIN) {
            MainScreen(
                onSub = { nav.navigate(Route.premium(fromSplash = false)) },
                onOpenReward = { nav.navigate(Route.REWARD) },
                onOpenInter = { nav.navigate(Route.INTER) },
                onOpenRewardInter = { nav.navigate(Route.REWARD_INTER) },
                onOpenAppOpen = { nav.navigate(Route.OPEN) },
                onOpenNativeFull = { nav.navigate(Route.NATIVE_FULL) },
            )
        }

        composable(Route.REWARD) { RewardScreen() }
        composable(Route.INTER) { InterstitialScreen() }
        composable(Route.REWARD_INTER) { RewardInterstitialScreen() }
        composable(Route.OPEN) { OpenScreen() }
        composable(Route.NATIVE_FULL) { NativeFullScreen(onClose = { nav.popBackStack() }) }
        composable(Route.ONBOARDING) {
            OnboardingScreen(onDone = {
                nav.navigate(Route.MAIN) { popUpTo(Route.ONBOARDING) { inclusive = true } }
            })
        }
    }
}

/** Hub chính: status premium + nút điều hướng + banner (chọn size, default SMART). Vào đây -> tắt onboard. */
@Composable
private fun MainScreen(
    onSub: () -> Unit,
    onOpenReward: () -> Unit,
    onOpenInter: () -> Unit,
    onOpenRewardInter: () -> Unit,
    onOpenAppOpen: () -> Unit,
    onOpenNativeFull: () -> Unit,
) {
    LaunchedEffect(Unit) { PreferencesManager.getInstance().setShowOnBoard(false) }

    var bannerSize by remember { mutableStateOf(AdmBannerSize.ADAPTIVE) }
    var bannerCollapsible by remember { mutableStateOf(AdmBannerCollapsible.NONE) }
    var nativeLayout by remember { mutableStateOf(NativeLayout.NORMAL) }

    // Preload native 1 lần -> lưu ngoài, truyền vào AdmNative (đổi layout chỉ re-render).
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }
    LaunchedEffect(Unit) { AdmNativeAd.preload(index = 1) { nativeAd = it } }
    DisposableEffect(Unit) { onDispose { nativeAd?.destroy() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF120726)),
    ) {
        // ----- Nội dung cuộn được -----
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .safeContentPadding()
                .padding(0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Main", color = Color(0xFF52DE11), fontSize = 32.sp, fontWeight = FontWeight.W700)

            PremiumStatusText(modifier = Modifier.padding(vertical = 24.dp))

            Button(onClick = onSub, modifier = Modifier.fillMaxWidth()) { Text("Subscription") }

            OutlinedButton(
                onClick = onOpenReward,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Reward Ad") }

            OutlinedButton(
                onClick = onOpenInter,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Interstitial") }

            OutlinedButton(
                onClick = onOpenRewardInter,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Rewarded Interstitial") }

            OutlinedButton(
                onClick = onOpenAppOpen,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("App Open") }

            OutlinedButton(
                onClick = onOpenNativeFull,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Native Full") }

            // ----- Chọn size banner -----
            Text(
                "Banner size",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AdmBannerSize.entries.forEach { size ->
                    FilterChip(
                        selected = bannerSize == size,
                        onClick = { bannerSize = size },
                        label = { Text(size.name) },
                    )
                }
            }

            // ----- Collapsible -----
            Text(
                "Collapsible",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdmBannerCollapsible.entries.forEach { c ->
                    FilterChip(
                        selected = bannerCollapsible == c,
                        onClick = { bannerCollapsible = c },
                        label = { Text(c.name) },
                    )
                }
            }

            // ----- Native (chọn layout, hiện shimmer lúc load) -----
            Text(
                "Native layout",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeLayout.entries.forEach { l ->
                    FilterChip(
                        selected = nativeLayout == l,
                        onClick = { nativeLayout = l },
                        label = { Text(l.name) },
                    )
                }
            }
            AdmNative(
                modifier = Modifier.fillMaxWidth(),
                layout = nativeLayout,
                nativeAd = nativeAd,                      // preload -> truyền vào, null thì skip
            ) {
                backgroundColor = Color(0xFF222B45)       // nền card
                ctaColor = Color(0xFFFF6D00)              // nền nút install (cam)
                ctaTextColor = Color.White                // chữ nút
                onPaid = { v -> Log.d("AdmNative", "paid $v") }
            }
            AdmNative(
                modifier = Modifier.fillMaxWidth(),
                layout = nativeLayout,
                index = 2                    // preload -> truyền vào, null thì skip
            ) {
                backgroundColor = Color(0xFF222B45)       // nền card
                ctaColor = Color(0xFFFF6D00)              // nền nút install (cam)
                ctaTextColor = Color.White                // chữ nút
                onPaid = { v -> Log.d("AdmNative", "paid $v") }
            }
        }

        // ----- Banner đáy màn: đổi size/collapsible tự reload (AdmBanner lo bên trong) -----
        AdmBanner(modifier = Modifier.fillMaxWidth()) {
            size = bannerSize
            collapsible = bannerCollapsible
            onError = { type -> Log.w("AdmBanner", "lỗi: $type") }
        }
    }
}
