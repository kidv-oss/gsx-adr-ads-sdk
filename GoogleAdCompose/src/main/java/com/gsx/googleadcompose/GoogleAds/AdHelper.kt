package com.gsx.googleadcompose.GoogleAds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * Compose helper cho ad full-screen — tạo instance riêng cho màn, tự destroy khi rời màn
 * (dọn preload buffer). [remember] giữ instance sống qua recompose; app khỏi tự viết
 * `remember{}` + `DisposableEffect { onDispose { destroy() } }`.
 *
 * ```
 * val reward = AdmReward { onReward = { grant() } }
 * reward.load()
 * reward.show()
 * ```
 */

/** Tạo [AdmRewardAd] riêng cho màn, tự destroy khi rời màn. */
@Composable
fun AdmReward(configure: AdmRewardAd.() -> Unit = {}): AdmRewardAd {
    val ad = remember { AdmRewardAd().apply(configure) }
    DisposableEffect(Unit) { onDispose { ad.destroy() } }
    return ad
}

/** Tạo [AdmInterstitialAd] riêng cho màn, tự destroy khi rời màn. */
@Composable
fun AdmInterstitial(configure: AdmInterstitialAd.() -> Unit = {}): AdmInterstitialAd {
    val ad = remember { AdmInterstitialAd().apply(configure) }
    DisposableEffect(Unit) { onDispose { ad.destroy() } }
    return ad
}

/** Tạo [AdmRewardInterstitialAd] riêng cho màn, tự destroy khi rời màn. */
@Composable
fun AdmRewardInterstitial(configure: AdmRewardInterstitialAd.() -> Unit = {}): AdmRewardInterstitialAd {
    val ad = remember { AdmRewardInterstitialAd().apply(configure) }
    DisposableEffect(Unit) { onDispose { ad.destroy() } }
    return ad
}

/** Tạo [AdmOpenAd] riêng cho màn, tự destroy khi rời màn. */
@Composable
fun AdmOpen(configure: AdmOpenAd.() -> Unit = {}): AdmOpenAd {
    val ad = remember { AdmOpenAd().apply(configure) }
    DisposableEffect(Unit) { onDispose { ad.destroy() } }
    return ad
}
