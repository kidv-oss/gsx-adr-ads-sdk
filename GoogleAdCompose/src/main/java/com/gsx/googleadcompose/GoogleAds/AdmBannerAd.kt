package com.gsx.googleadcompose.GoogleAds

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.mediation.admob.AdMobAdapter
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadResult
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.gsx.googleadcompose.data.AdmConfigAdId
import com.gsx.googleadcompose.error.AdmErrorType
import com.gsx.googleadcompose.helper.NetworkHelper
import com.gsx.googleadcompose.utils.PreferencesManager

/**
 * Kích cỡ banner. Next-Gen SDK BỎ legacy `SMART_BANNER` -> [ADAPTIVE] = adaptive anchored full-width
 * (thay thế chính thức của Google: full màn rộng, cao tự tính theo thiết bị). Mặc định [ADAPTIVE].
 */
enum class AdmBannerSize {
    /** Adaptive anchored full-width theo orientation hiện tại — thay smart_banner. Mặc định. */
    ADAPTIVE,
    /** Large adaptive anchored — cao hơn, eCPM tốt hơn (~90-100dp). */
    ADAPTIVE_LARGE,
    /** Cố định 320x50. */
    BANNER,
    /** Cố định 468x60. */
    FULL_BANNER,
    /** Cố định 320x100. */
    LARGE_BANNER,
    /** Cố định 728x90 (tablet). */
    LEADERBOARD,
    /** Cố định 300x250 (MREC). */
    MEDIUM_RECTANGLE,
}

/**
 * Banner thu gọn (collapsible): load ở cỡ to rồi có nút thu nhỏ về anchored. [BOTTOM]/[TOP] = mép
 * ad mở rộng canh đáy/đỉnh banner view. Chỉ hợp với size anchored adaptive ([AdmBannerSize.ADAPTIVE]
 * /[AdmBannerSize.ADAPTIVE_LARGE]). [NONE] = banner thường.
 */
enum class AdmBannerCollapsible(internal val value: String) {
    NONE(""), TOP("top"), BOTTOM("bottom")
}

/**
 * Banner ad (GMA Next-Gen SDK) theo pattern Google git `ComposeBannerFragment`:
 * `BannerAd.load(...)` (suspend) -> `bannerAd.getView(activity)` render trong [AndroidView].
 * Khác ad full-screen (preloader/show): banner gắn liền View nên logic gói luôn trong
 * composable [AdmBanner]. Log tag `GoogleAds/Banner`.
 *
 * Controller giữ guard (premium/network/UMP/units), xoay unit id, gắn callback + paid event.
 * Unit ID lấy từ [AdmConfigAdId.listBannerAdUnitID] (nhiều ID -> xoay vòng mỗi lần load).
 * Cần init MobileAds (UMP) trước.
 *
 * ```
 * AdmBanner(Modifier.fillMaxWidth()) {            // tự load, tự fill view, tự destroy khi rời màn
 *     onError = { type -> Log.w("ad", "$type") }
 *     size = AdmBannerSize.LARGE
 * }
 * ```
 */
class AdmBannerAd internal constructor() : BannerAdEventCallback {

    var enableLogging: Boolean = true
    /** Cỡ banner. Mặc định [AdmBannerSize.ADAPTIVE] (adaptive full-width). */
    var size: AdmBannerSize = AdmBannerSize.ADAPTIVE
    /** Collapsible: thu gọn về anchored. Mặc định [AdmBannerCollapsible.NONE]. */
    var collapsible: AdmBannerCollapsible = AdmBannerCollapsible.NONE
    /** Bề rộng (dp) để tính adaptive size. <=0 -> tự lấy bề rộng màn. */
    var adWidthDp: Int = 0

    /** Kênh báo lỗi (xem [AdmErrorType]). Premium/offline/UMP chưa sẵn -> báo về đây, không render. */
    var onError: (AdmErrorType) -> Unit = {}

    // ---- Hook sự kiện ----
    /** Ad load xong -> trả [BannerAd] (lấy `.responseInfo` nếu cần). */
    var onLoaded: (BannerAd) -> Unit = {}
    var onImpression: () -> Unit = {}
    var onClicked: () -> Unit = {}
    var onPaid: (AdValue) -> Unit = {}   // doanh thu (paid event)
    var onRefreshed: () -> Unit = {}

    private var currentIndex: Int = -1
    private var unitId: String? = null
    private fun tag(): String = "[#$currentIndex ${unitId ?: "-"}]"

    /** Ad đang giữ (đã load xong). */
    @Volatile
    var bannerAd: BannerAd? = null
        private set

    /** True khi premium -> không nên hiện banner. */
    fun isBlockedByPremium(): Boolean = premiumBlock() != null

    /** Loại lỗi premium (removeAds/lifetime/sub) chặn banner, null nếu không bị chặn. */
    fun premiumBlockError(): AdmErrorType? = premiumBlock()

    // ============================ Load ============================

    /**
     * Load 1 banner (suspend). Trả [BannerAd] hoặc null (kèm [onError]). [index] = -1 xoay vòng,
     * >=0 chọn id cụ thể trong [AdmConfigAdId.listBannerAdUnitID].
     */
    suspend fun loadAd(activity: Activity, index: Int = -1, customIds: List<String>? = null): BannerAd? {
        envBlock()?.let { fail(it); return null }

        val u = customIds?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: units()
        if (u.isEmpty()) { fail(AdmErrorType.LIST_AD_ID_IS_EMPTY); return null }
        val idx = if (index < 0) nextIndex(u.size) else index
        val unit = u.getOrNull(idx) ?: run { fail(AdmErrorType.AD_ID_IS_NOT_EXIST); return null }
        unitId = unit
        currentIndex = idx

        val adSize = adSize(activity)
        log("load ${tag()} size=$adSize collapsible=$collapsible${if (customIds != null) " [custom]" else ""}")
        val builder = BannerAdRequest.Builder(unit, adSize)
        if (collapsible != AdmBannerCollapsible.NONE) {
            val extras = Bundle().apply { putString("collapsible", collapsible.value) }
            builder.putAdSourceExtrasBundle(AdMobAdapter::class.java, extras)
        }
        return when (val result = BannerAd.load(builder.build())) {
            is AdLoadResult.Success -> {
                val ad = result.ad
                ad.adEventCallback = this
                ad.bannerAdRefreshCallback = object : BannerAdRefreshCallback {
                    override fun onAdRefreshed() { log("refreshed ${tag()}"); onRefreshed() }
                    override fun onAdFailedToRefresh(adError: LoadAdError) {
                        log("refresh FAIL ${tag()}: ${adError.code} ${adError.message}")
                    }
                }
                bannerAd = ad
                log("loaded ${tag()}")
                onLoaded(ad)
                ad
            }
            is AdLoadResult.Failure -> {
                log("load FAIL ${tag()}: ${result.error.code} ${result.error.message}")
                fail(AdmErrorType.AD_IS_NOT_AVAILABLE)
                null
            }
        }
    }

    /** Hủy ad (gọi khi rời màn). */
    fun destroy() {
        log("destroy ${tag()}")
        bannerAd?.destroy()
        bannerAd = null
    }

    // =========== BannerAdEventCallback (kế thừa AdEventCallback) ===========

    override fun onAdImpression() { log("impression ${tag()}"); onImpression() }
    override fun onAdClicked() { log("clicked ${tag()}"); onClicked() }
    override fun onAdPaid(value: AdValue) {
        log("paid ${tag()}: ${value.valueMicros} ${value.currencyCode}")
        onPaid(value)
    }

    // ======================== Helpers ===========================

    private fun adSize(activity: Activity): AdSize {
        val width = if (adWidthDp > 0) adWidthDp else screenWidthDp(activity)
        // Collapsible CHỈ chạy với anchored adaptive -> size fixed/MREC tự ép về ADAPTIVE.
        val effective = if (collapsible != AdmBannerCollapsible.NONE &&
            size != AdmBannerSize.ADAPTIVE && size != AdmBannerSize.ADAPTIVE_LARGE
        ) {
            log("collapsible -> ép size $size thành ADAPTIVE")
            AdmBannerSize.ADAPTIVE
        } else size
        return when (effective) {
            AdmBannerSize.ADAPTIVE -> AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, width)
            AdmBannerSize.ADAPTIVE_LARGE -> AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, width)
            AdmBannerSize.BANNER -> AdSize.BANNER
            AdmBannerSize.FULL_BANNER -> AdSize.FULL_BANNER
            AdmBannerSize.LARGE_BANNER -> AdSize.LARGE_BANNER
            AdmBannerSize.LEADERBOARD -> AdSize.LEADERBOARD
            AdmBannerSize.MEDIUM_RECTANGLE -> AdSize.MEDIUM_RECTANGLE
        }
    }

    private fun screenWidthDp(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        return (dm.widthPixels / dm.density).toInt()
    }

    private fun premiumBlock(): AdmErrorType? {
        val pm = PreferencesManager.getInstance()
        return when {
            pm.isRemoveAds() || pm.isLifetime() -> AdmErrorType.CLIENT_HAVE_BEEN_REMOVED_AD
            pm.isSUB() -> AdmErrorType.CLIENT_HAVE_SUB
            else -> null
        }
    }

    /** Guard môi trường (premium/UMP/network) — KHÔNG gồm check list rỗng. */
    private fun envBlock(): AdmErrorType? {
        premiumBlock()?.let { return it }
        if (!AdCore.isMobileAdsReady) return AdmErrorType.UMP_IS_NOT_ACTIVE
        val ctx = AdCore.appContext
        if (ctx != null && !NetworkHelper.isOnline(ctx)) return AdmErrorType.NETWORK_IS_NOT_AVAILABLE
        return null
    }

    private fun units(): List<String> = AdmConfigAdId.listBannerAdUnitID.filter { it.isNotBlank() }

    /** Index kế tiếp (xoay vòng), advance cursor chung mọi instance. */
    private fun nextIndex(size: Int): Int {
        val i = cursor % size
        cursor = (cursor + 1) % size
        return i
    }

    private fun fail(type: AdmErrorType) {
        log("ERROR: $type")
        onError(type)
    }

    private fun log(msg: String) {
        if (enableLogging) Log.d(TAG, msg)
    }

    private companion object {
        const val TAG = "GoogleAds/Banner"
        private var cursor = 0   // xoay vòng unit id chung mọi instance
    }
}

/**
 * Compose banner — tự load, tự fill vào view, tự destroy khi rời màn. Premium/offline/UMP chưa sẵn
 * -> render rỗng (báo qua [AdmBannerAd.onError]). Đặt ở đáy màn (anchored) hay bất kỳ đâu.
 *
 * ```
 * AdmBanner(Modifier.fillMaxWidth())                    // mặc định
 * AdmBanner { size = AdmBannerSize.LARGE; onPaid = { v -> track(v) } }   // tùy biến
 * ```
 */
@Composable
fun AdmBanner(
    modifier: Modifier = Modifier,
    index: Int = -1,
    customIds: List<String>? = null,
    configure: AdmBannerAd.() -> Unit = {},
) {
    val controller = remember { AdmBannerAd() }
    // Re-apply mỗi recomposition -> nhận size/collapsible/onError mới (setter rẻ, không tốn gì).
    controller.apply(configure)

    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val activity = remember(context) { context.findActivity() ?: AdCore.currentActivity }
    var ad by remember { mutableStateOf<BannerAd?>(null) }
    var failed by remember { mutableStateOf(false) }
    val blocked = remember { controller.isBlockedByPremium() }

    // Key theo size/collapsible -> đổi cấu hình tự reload, call-site KHỎI cần key() bọc ngoài.
    // customIds != null -> xoay vòng list id đó thay cho AdmConfigAdId.listBannerAdUnitID.
    LaunchedEffect(activity, index, customIds, controller.size, controller.collapsible) {
        if (isPreview) return@LaunchedEffect
        if (blocked) {
            // Premium/removeAds -> báo error (không im lặng), rồi render rỗng.
            controller.onError(controller.premiumBlockError() ?: AdmErrorType.CLIENT_HAVE_BEEN_REMOVED_AD)
            failed = true
            return@LaunchedEffect
        }
        if (activity == null) {
            controller.onError(AdmErrorType.ACTIVITY_IS_NOT_AVAILABLE); failed = true; return@LaunchedEffect
        }
        controller.destroy()   // dọn ad cũ trước khi load cỡ mới
        ad = null
        failed = false
        // loadAd tự guard premium/UMP/offline/units -> fail() bắn onError (parity với inter).
        val result = controller.loadAd(activity, index, customIds)
        ad = result
        failed = result == null
    }

    DisposableEffect(Unit) { onDispose { controller.destroy() } }

    val loaded = ad
    when {
        blocked -> Unit
        loaded != null && activity != null ->
            Box(modifier.fillMaxWidth().wrapContentHeight(), contentAlignment = Alignment.Center) {
                AndroidView(factory = { loaded.getView(activity) })
            }
        failed -> Unit                                                  // lỗi -> rỗng (đã báo onError)
        else -> AdShimmerBanner(modifier, heightDp = controller.size.placeholderHeightDp())  // đang load
    }
}

/** Chiều cao xấp xỉ (dp) cho skeleton shimmer theo cỡ banner. */
private fun AdmBannerSize.placeholderHeightDp(): Int = when (this) {
    AdmBannerSize.ADAPTIVE -> 60
    AdmBannerSize.ADAPTIVE_LARGE -> 90
    AdmBannerSize.BANNER -> 50
    AdmBannerSize.FULL_BANNER -> 60
    AdmBannerSize.LARGE_BANNER -> 100
    AdmBannerSize.LEADERBOARD -> 90
    AdmBannerSize.MEDIUM_RECTANGLE -> 250
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
