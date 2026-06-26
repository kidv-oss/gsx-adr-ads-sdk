package com.gsx.googleadcompose.GoogleAds

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdPreloader
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.gsx.googleadcompose.GlobalVariables
import com.gsx.googleadcompose.data.AdmConfigAdId
import com.gsx.googleadcompose.error.AdmErrorType
import com.gsx.googleadcompose.helper.NetworkHelper
import com.gsx.googleadcompose.utils.PreferencesManager

/**
 * App Open ad (GMA Next-Gen SDK) — **[AppOpenAdPreloader]**: preload buffer, show bằng `pollAd`,
 * SDK tự nạp tiếp. Log tag `GoogleAds/Open`. KHÔNG dialog loading.
 *
 * `load`/`show` private (nội bộ). 2 cách dùng công khai:
 * - [resumeAd] — tự show khi app trở lại foreground (gọi 1 lần, vd Application qua [AdmOpenResume]).
 * - [showFromSplash] — load + show ngay cho cold-start trên splash.
 *
 * Cả hai nhận [index]: -1 xoay vòng, >=0 chọn id cụ thể trong [AdmConfigAdId.listOpenAdUnitID].
 * Mọi sự kiện vẫn trả qua hook ([onShowed]/[onDismissed]/[onError]/[onPaid]/...). Cần init MobileAds trước.
 */
class AdmOpenAd internal constructor() : AppOpenAdEventCallback {

    var enableLogging: Boolean = true
    /** Số ad giữ sẵn trong buffer (mặc định SDK = 1). */
    var bufferSize: Int = 1

    /** Kênh báo lỗi duy nhất (xem [AdmErrorType]). */
    var onError: (AdmErrorType) -> Unit = {}

    // ---- Hook sự kiện cho app ----
    /** Buffer có ad sẵn (preload xong) -> trả [ResponseInfo] (ad object poll lúc show). */
    var onAvailable: (ResponseInfo) -> Unit = {}
    var onExhausted: () -> Unit = {}
    var onShowed: () -> Unit = {}
    var onImpression: () -> Unit = {}
    var onClicked: () -> Unit = {}
    var onPaid: (AdValue) -> Unit = {}
    var onDismissed: () -> Unit = {}
    var onComplete: () -> Unit = {}

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Unit id đang preload. */
    private var preloadUnitId: String? = null
    private var currentIndex: Int = -1
    private fun tag(): String = "[#$currentIndex ${preloadUnitId ?: "-"}]"

    private var popupOwned = false   // instance này đang giữ mutex isShowPopup
    private var loadedAtMs = 0L       // mốc preload (app-open hết hạn sau 4h)

    // ---- Chờ preload để show (load+show) ----
    private var wantShow = false
    private var wantIndex = -1
    private var wantCustomIds: List<String>? = null

    // ---- Splash: callback đóng/lỗi/timeout (1-shot) ----
    private var splashClosed: (() -> Unit)? = null
    private var splashTimeout: Runnable? = null

    // ---- Auto-show theo foreground (resume) ----
    private var resumeObserver: LifecycleEventObserver? = null
    private var resumeIndex = -1
    private var skipNextStart = false

    /** True khi buffer có ad sẵn để show ngay. */
    val isReady: Boolean
        get() = preloadUnitId?.let { AppOpenAdPreloader.isAdAvailable(it) } == true

    // ========================= Công khai ========================

    /**
     * Bật auto-show app-open khi app trở lại foreground (ProcessLifecycle ON_START). Tự preload,
     * mỗi resume kiểm tra [GlobalVariables.canShowOpenAd] + [GlobalVariables.isShowPopup].
     *
     * @param index -1 xoay vòng / >=0 id cụ thể.
     * @param skipFirstStart bỏ qua ON_START đầu (cold start / splash đang lo).
     */
    fun resumeAd(index: Int = -1, skipFirstStart: Boolean = true) {
        if (resumeObserver != null) { log("resumeAd đã bật"); return }
        if (bailIfPremium()) { log("resumeAd: premium -> không bật"); return }  // premium: không preload/observer
        resumeIndex = index
        skipNextStart = skipFirstStart
        load(index)
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                // Mua premium runtime -> dọn preloader + gỡ observer (ngừng hẳn).
                if (bailIfPremium()) {
                    resumeObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
                    resumeObserver = null
                    return@LifecycleEventObserver
                }
                if (skipNextStart) { skipNextStart = false; log("ON_START đầu -> bỏ qua, chỉ preload"); return@LifecycleEventObserver }
                if (!GlobalVariables.canShowOpenAd) { log("canShowOpenAd=false -> bỏ"); return@LifecycleEventObserver }
                show(index, waitForLoad = false)   // chưa sẵn -> chỉ preload, KHÔNG show trễ
            }
        }
        resumeObserver = obs
        ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
        log("resumeAd bật")
    }

    /**
     * Cold-start splash: load + show. [onClosed] fire 1 lần khi **ad đóng / lỗi / quá [timeoutMs]**
     * (preload chưa kịp) -> splash gọi tiếp vào main. [index] chọn id.
     */
    fun showFromSplash(
        index: Int = -1,
        customIds: List<String>? = null,
        timeoutMs: Long = 8_000L,
        onClosed: () -> Unit,
    ) {
        // User chủ động gọi splash open -> bỏ qua canShowOpenAd (đã đồng ý show). Resume vẫn check.
        splashClosed = onClosed
        val t = Runnable { log("splash open timeout ${timeoutMs}ms"); consumeSplash() }
        splashTimeout = t
        mainHandler.postDelayed(t, timeoutMs)
        show(index, customIds = customIds, force = true)
    }

    /** Fire callback splash 1-shot (đóng/lỗi/timeout) + dọn. Đảm bảo chạy trên main (navigate an toàn). */
    private fun consumeSplash() {
        splashTimeout?.let { mainHandler.removeCallbacks(it) }
        splashTimeout = null
        wantShow = false
        val cb = splashClosed ?: return
        splashClosed = null
        mainHandler.post { cb() }   // callback có thể đến từ thread ad/preload -> ép về main
    }

    /** Dừng preload + gỡ observer resume (gọi khi rời màn / tắt). */
    fun destroy() {
        log("destroy ${tag()}")
        releasePopup()
        wantShow = false
        splashTimeout?.let { mainHandler.removeCallbacks(it) }
        splashTimeout = null
        splashClosed = null
        resumeObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        resumeObserver = null
        preloadUnitId?.let { AppOpenAdPreloader.destroy(it) }
        preloadUnitId = null
    }

    // ========================= Nội bộ ===========================

    /** Preload buffer cho id theo [index] (-1 xoay vòng). canShowOpenAd=false / đang preload -> bỏ. */
    private fun load(index: Int = -1, customIds: List<String>? = null, force: Boolean = false) {
        if (!force && !GlobalVariables.canShowOpenAd) { log("canShowOpenAd=false -> không load"); return }
        if (bailIfPremium()) return                 // premium -> dọn buffer + return
        preloadError()?.let { fail(it); return }    // còn lại: UMP/network/empty
        if (preloadUnitId != null) return                          // đang preload -> bỏ qua

        // customIds != null -> xoay vòng list đó thay cho AdmConfigAdId.listOpenAdUnitID.
        val u = customIds?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: units()
        val idx = if (index < 0) nextIndex(u.size) else index
        val unit = u.getOrNull(idx) ?: run { fail(AdmErrorType.AD_ID_IS_NOT_EXIST); return }
        preloadUnitId = unit
        currentIndex = idx
        log("load (preload) ${tag()}${if (customIds != null) " [custom]" else ""}")

        val config = PreloadConfiguration(AdRequest.Builder(unit).build(), bufferSize)
        AppOpenAdPreloader.start(unit, config, object : PreloadCallback {
            override fun onAdPreloaded(preloadId: String, responseInfo: ResponseInfo) {
                loadedAtMs = System.currentTimeMillis()
                log("preloaded ${tag()}"); onAvailable(responseInfo)
                mainHandler.post { if (wantShow) { wantShow = false; show(wantIndex, customIds = wantCustomIds) } }
            }
            override fun onAdsExhausted(preloadId: String) {
                onExhausted()
            }
            override fun onAdFailedToPreload(preloadId: String, adError: LoadAdError) {
                log("preload FAIL ${tag()}: ${adError.code} ${adError.message}")
                wantShow = false
                fail(AdmErrorType.AD_IS_NOT_AVAILABLE)
            }
        })
    }

    /**
     * Sẵn + còn hạn -> show NGAY. Chưa sẵn: [waitForLoad] true -> load([index]) rồi show khi xong
     * (splash); false -> chỉ preload, KHÔNG show (resume: bỏ lần này, foreground sau mới show).
     * Hết hạn (>4h) -> reload. Premium / popup khác đang hiện -> bỏ.
     */
    private fun show(index: Int = -1, waitForLoad: Boolean = true, customIds: List<String>? = null, force: Boolean = false) {
        val act = AdCore.currentActivity ?: run { fail(AdmErrorType.ACTIVITY_IS_NOT_AVAILABLE); return }
        if (act.isDead()) { fail(AdmErrorType.ACTIVITY_IS_NOT_AVAILABLE); return }
        premiumBlock()?.let { fail(it); return }
        if (GlobalVariables.isShowPopup) { log("full-screen/dialog khác đang hiện, bỏ qua"); return }

        val id = preloadUnitId
        if (id != null && AppOpenAdPreloader.isAdAvailable(id) && !isExpired()) {
            markPopup(); pollAndShow(act, id); return
        }
        // Chưa sẵn / hết hạn -> load lại.
        if (id != null && isExpired()) reloadFresh(index, customIds, force) else if (preloadUnitId == null) load(index, customIds, force)
        if (waitForLoad) {
            wantShow = true
            wantIndex = index
            wantCustomIds = customIds
            log("chưa sẵn -> load rồi show ${tag()}")
        } else {
            log("chưa sẵn -> chỉ preload, bỏ show lần này ${tag()}")
        }
    }

    /** Bỏ ad cũ + preload lại id theo [index]/[customIds] (không gỡ observer như [destroy]). */
    private fun reloadFresh(index: Int, customIds: List<String>? = null, force: Boolean = false) {
        preloadUnitId?.let { AppOpenAdPreloader.destroy(it) }
        preloadUnitId = null
        loadedAtMs = 0L
        load(index, customIds, force)
    }

    /** Ad đã preload quá 4h -> coi như hết hạn (Google: app-open valid 4 giờ). */
    private fun isExpired(): Boolean =
        loadedAtMs == 0L || (System.currentTimeMillis() - loadedAtMs) >= FOUR_HOURS_MS

    private fun pollAndShow(activity: Activity, id: String) {
        val ad = AppOpenAdPreloader.pollAd(id)
        if (ad == null) { log("pollAd null ${tag()}"); fail(AdmErrorType.AD_IS_NOT_AVAILABLE); return }
        log("show ${tag()}")
        ad.adEventCallback = this
        ad.show(activity)
    }

    // ================ AppOpenAdEventCallback ====================

    override fun onAdShowedFullScreenContent() {
        log("showed ${tag()}")
        splashTimeout?.let { mainHandler.removeCallbacks(it) }   // đã show -> không để timeout skip
        splashTimeout = null
        onShowed()
    }
    override fun onAdImpression() { onImpression() }
    override fun onAdClicked() { onClicked() }
    override fun onAdPaid(value: AdValue) { onPaid(value) }

    override fun onAdDismissedFullScreenContent() {
        log("dismissed ${tag()}")
        releasePopup()
        onDismissed()
        onComplete()
        consumeSplash()       // ad đóng -> splash vào main
        load(resumeIndex)     // dismiss -> nạp ad mới cho lần sau (no-op nếu buffer đang refill)
    }

    override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
        log("show FAIL ${tag()}: $fullScreenContentError")
        fail(AdmErrorType.AD_IS_NOT_AVAILABLE)
    }

    // ======================== Helpers ===========================

    private fun Activity.isDead(): Boolean = isFinishing || isDestroyed

    private fun premiumBlock(): AdmErrorType? {
        val pm = PreferencesManager.getInstance()
        return when {
            pm.isRemoveAds() || pm.isLifetime() -> AdmErrorType.CLIENT_HAVE_BEEN_REMOVED_AD
            pm.isSUB() -> AdmErrorType.CLIENT_HAVE_SUB
            else -> null
        }
    }

    /**
     * Premium -> DỌN preloader buffer đang chạy + báo lỗi, trả true (caller phải return).
     * Khác guard thường: preloader tự giữ buffer, chỉ `return` không đủ -> phải destroy.
     */
    private fun bailIfPremium(): Boolean {
        val err = premiumBlock() ?: return false
        preloadUnitId?.let { AppOpenAdPreloader.destroy(it) }
        preloadUnitId = null
        fail(err)
        return true
    }

    private fun preloadError(): AdmErrorType? {
        premiumBlock()?.let { return it }
        if (!AdCore.isMobileAdsReady) return AdmErrorType.UMP_IS_NOT_ACTIVE
        val ctx = AdCore.appContext
        if (ctx != null && !NetworkHelper.isOnline(ctx)) return AdmErrorType.NETWORK_IS_NOT_AVAILABLE
        if (units().isEmpty()) return AdmErrorType.LIST_AD_ID_IS_EMPTY
        return null
    }

    private fun units(): List<String> = AdmConfigAdId.listOpenAdUnitID.filter { it.isNotBlank() }

    private fun nextIndex(size: Int): Int {
        val i = cursor % size
        cursor = (cursor + 1) % size
        return i
    }

    private fun fail(type: AdmErrorType) {
        log("ERROR: $type")
        releasePopup()
        onError(type)
        consumeSplash()   // lỗi (load/show/no-activity/premium) -> splash vào main
    }

    private fun markPopup() {
        GlobalVariables.isShowPopup = true
        popupOwned = true
    }

    private fun releasePopup() {
        if (popupOwned) {
            GlobalVariables.isShowPopup = false
            popupOwned = false
        }
    }

    private fun log(msg: String) {
        if (enableLogging) Log.d(TAG, msg)
    }

    private companion object {
        const val TAG = "GoogleAds/Open"
        const val FOUR_HOURS_MS = 4L * 60 * 60 * 1000   // app-open hết hạn sau 4h (Google)
        private var cursor = 0
    }
}
