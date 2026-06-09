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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
                    Route.premium(fromSplash = true) else Route.MAIN
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
            )
        }

        composable(Route.REWARD) { RewardScreen() }
        composable(Route.INTER) { InterstitialScreen() }
        composable(Route.REWARD_INTER) { RewardInterstitialScreen() }
        composable(Route.OPEN) { OpenScreen() }
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
) {
    LaunchedEffect(Unit) { PreferencesManager.getInstance().setShowOnBoard(false) }

    var bannerSize by remember { mutableStateOf(AdmBannerSize.ADAPTIVE) }
    var bannerCollapsible by remember { mutableStateOf(AdmBannerCollapsible.NONE) }

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
                .padding(24.dp),
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
        }

        // ----- Banner đáy màn: đổi size/collapsible tự reload (AdmBanner lo bên trong) -----
        AdmBanner(modifier = Modifier.fillMaxWidth()) {
            size = bannerSize
            collapsible = bannerCollapsible
            onError = { type -> Log.w("AdmBanner", "lỗi: $type") }
        }
    }
}
