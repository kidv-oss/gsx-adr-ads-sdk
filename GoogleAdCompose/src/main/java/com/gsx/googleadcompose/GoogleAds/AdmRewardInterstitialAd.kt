package com.gsx.googleadcompose.GoogleAds

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdPreloader
import com.gsx.googleadcompose.GlobalVariables
import com.gsx.googleadcompose.data.AdmConfigAdId
import com.gsx.googleadcompose.error.AdmErrorType
import com.gsx.googleadcompose.helper.DialogHelper
import com.gsx.googleadcompose.helper.NetworkHelper
import com.gsx.googleadcompose.utils.PreferencesManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rewarded Interstitial ad (GMA Next-Gen SDK) theo pattern Google git `RewardedInterstitialFragment`
 * — dùng **[RewardedInterstitialAdPreloader]**: preload sẵn vào buffer, show lấy ra bằng `pollAd`,
 * SDK tự nạp quảng cáo kế tiếp ngầm. Log tag `GoogleAds/RewardInter`.
 *
 * [load] = bật preload (gọi sớm). [show]:
 * - Buffer đã có ad -> hiện dialog [showDelayMs] rồi show.
 * - Buffer chưa kịp load -> dialog loading, chờ buffer (timeout [loadTimeoutMs] thì hủy + [onError]).
 *   Poll luôn gọi NGOÀI [PreloadCallback] (yêu cầu Google).
 *
 * ```
 * val ad = AdmRewardInterstitial {     // helper -> [AdmRewardInterstitialAd], tự destroy
 *     onReward = { item -> grant(item.amount) }
 *     onError  = { type -> toast(type) }
 * }
 * ad.load()
 * ad.show()
 * ```
 *
 * Unit ID lấy từ [AdmConfigAdId.listRewardInterstitialAdUnitID] (nhiều ID -> xoay vòng mỗi lần [load]).
 * Cần init MobileAds (UMP) trước.
 */
class AdmRewardInterstitialAd internal constructor() : RewardedInterstitialAdEventCallback {

    var enableLogging: Boolean = true
    var loadingText: String = "Loading..."
    /** Show khi buffer chưa sẵn -> chờ tối đa ngần này rồi hủy. */
    var loadTimeoutMs: Long = 12_000L
    /** Ad đã sẵn vẫn hiện dialog ngần này trước khi show (cho mượt). */
    var showDelayMs: Long = 2_000L
    /** Số ad giữ sẵn trong buffer (mặc định SDK = 1). */
    var bufferSize: Int = 1

    /** Kênh báo lỗi duy nhất (xem [AdmErrorType]). */
    var onError: (AdmErrorType) -> Unit = {}

    // ---- Hook sự kiện cho app ----
    var onReward: (RewardItem) -> Unit = {}
    /** Buffer có ad sẵn (preload xong) -> trả [ResponseInfo] (ad object poll lúc show). */
    var onAvailable: (ResponseInfo) -> Unit = {}
    var onExhausted: () -> Unit = {}     // buffer hết ad (PreloadCallback.onAdsExhausted)
    var onShowed: () -> Unit = {}
    var onImpression: () -> Unit = {}
    var onClicked: () -> Unit = {}
    var onPaid: (AdValue) -> Unit = {}   // doanh thu (paid event)
    var onDismissed: () -> Unit = {}
    var onComplete: () -> Unit = {}

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Unit id đang preload (poll/isAvailable đều theo id này). */
    private var preloadUnitId: String? = null
    private var currentIndex: Int = -1
    private fun tag(): String = "[#$currentIndex ${preloadUnitId ?: "-"}]"

    // ---- Trạng thái "show đang chờ buffer" ----
    private var pendingActivity: Activity? = null
    private var pendingDialog: AlertDialog? = null
    private var pendingDone: AtomicBoolean? = null
    private var pendingTimeout: Runnable? = null
    private var popupOwned = false   // instance này đang giữ mutex isShowPopup

    /** True khi buffer có ad sẵn để show ngay. */
    val isReady: Boolean
        get() = preloadUnitId?.let { RewardedInterstitialAdPreloader.isAdAvailable(it) } == true

    // ============================ API ============================

    /**
     * Bật preload. [index] = -1 -> xoay vòng unit id kế tiếp; >=0 -> chọn cụ thể id thứ [index]
     * (0-based) trong [AdmConfigAdId.listRewardInterstitialAdUnitID]. Đang preload rồi -> bỏ qua. Lỗi -> [onError].
     */
    fun load(index: Int = -1, customIds: List<String>? = null) {
        if (bailIfPremium()) return                 // premium -> dọn buffer + onError
        preloadError()?.let { fail(it); return }
        if (preloadUnitId != null) { log("đang preload ${tag()}, bỏ qua"); return }

        // customIds != null -> xoay vòng list đó thay cho AdmConfigAdId.listRewardInterstitialAdUnitID.
        val u = customIds?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: units()
        val idx = if (index < 0) nextIndex(u.size) else index
        val unit = u.getOrNull(idx) ?: run { fail(AdmErrorType.AD_ID_IS_NOT_EXIST); return }
        preloadUnitId = unit
        currentIndex = idx
        log("load (preload) ${tag()}${if (customIds != null) " [custom]" else ""}")

        val config = PreloadConfiguration(AdRequest.Builder(unit).build(), bufferSize)
        RewardedInterstitialAdPreloader.start(unit, config, object : PreloadCallback {
            // ⚠ Google: KHÔNG gọi start/poll trực tiếp trong callback này -> post ra ngoài.
            override fun onAdPreloaded(preloadId: String, responseInfo: ResponseInfo) {
                log("preloaded ${tag()}")
                onAvailable(responseInfo)
                mainHandler.post { showPendingIfAny() }
            }

            override fun onAdsExhausted(preloadId: String) {
                log("exhausted ${tag()}")
                onExhausted()
            }

            override fun onAdFailedToPreload(preloadId: String, adError: LoadAdError) {
                log("preload FAIL ${tag()}: ${adError.code} ${adError.message}")
                mainHandler.post { failPending(AdmErrorType.AD_IS_NOT_AVAILABLE) }
            }
        })
    }

    /**
     * Show dùng activity foreground tự lấy ([AdCore.currentActivity]). Không có -> [onError].
     * [index]: chỉ dùng khi CHƯA preload -> load id đó rồi show (xem [show]).
     */
    fun show(index: Int = -1, customIds: List<String>? = null) {
        val act = AdCore.currentActivity ?: run { fail(AdmErrorType.ACTIVITY_IS_NOT_AVAILABLE); return }
        show(act, index, customIds)
    }

    /**
     * Show ad. Sẵn -> dialog showDelayMs rồi show. Chưa kịp load -> bật preload (load id theo [index])
     * + dialog chờ buffer. [index] = -1 xoay vòng, >=0 chọn id cụ thể (CHỈ khi chưa có buffer; đã
     * preload id khác thì show id đang có). Đang có 1 show chờ -> bỏ qua (double-tap). Lỗi -> [onError].
     */
    fun show(activity: Activity, index: Int = -1, customIds: List<String>? = null) {
        if (activity.isDead()) { fail(AdmErrorType.ACTIVITY_IS_NOT_AVAILABLE); return }
        premiumBlock()?.let { fail(it); return }
        if (pendingActivity != null) { log("đang chờ show, bỏ qua (double-tap)"); return }
        if (GlobalVariables.isShowPopup) { log("full-screen/dialog khác đang hiện, bỏ qua"); return }

        val id = preloadUnitId
        if (id != null && RewardedInterstitialAdPreloader.isAdAvailable(id)) { showWithDelay(activity, id); return }

        preloadError()?.let { fail(it); return }
        if (preloadUnitId == null) load(index, customIds)   // chưa preload -> load+show id từ customIds
        waitForAd(activity)
    }

    /** Ad đã sẵn: hiện dialog [showDelayMs] rồi poll + show. */
    private fun showWithDelay(activity: Activity, id: String) {
        log("ad sẵn, dialog ${showDelayMs}ms rồi show ${tag()}")
        markPopup()
        pendingActivity = activity
        pendingDialog = DialogHelper.showLoading(activity, loadingText)
        val done = AtomicBoolean(false)
        pendingDone = done
        val run = Runnable {
            if (done.compareAndSet(false, true)) {
                cleanupPending()
                if (activity.isDead()) fail(AdmErrorType.ACTIVITY_IS_NOT_AVAILABLE)
                else pollAndShow(activity, id)
            }
        }
        pendingTimeout = run
        mainHandler.postDelayed(run, showDelayMs)
    }

    /** Dừng preload, dọn buffer + hủy show đang chờ (gọi khi rời màn). */
    fun destroy() {
        log("destroy ${tag()}")
        clearPending()
        releasePopup()
        preloadUnitId?.let { RewardedInterstitialAdPreloader.destroy(it) }
        preloadUnitId = null
    }

    // ===================== Show chờ buffer ======================

    private fun waitForAd(activity: Activity) {
        log("chờ buffer... ${tag()}")
        markPopup()
        pendingActivity = activity
        pendingDialog = DialogHelper.showLoading(activity, loadingText)
        val done = AtomicBoolean(false)
        pendingDone = done
        val timeout = Runnable {
            if (done.compareAndSet(false, true)) {
                log("timeout chờ buffer ${tag()}")
                cleanupPending()
                fail(AdmErrorType.AD_IS_NOT_AVAILABLE)
            }
        }
        pendingTimeout = timeout
        mainHandler.postDelayed(timeout, loadTimeoutMs)
    }

    private fun showPendingIfAny() {
        val act = pendingActivity ?: return
        val id = preloadUnitId ?: return
        val done = pendingDone ?: return
        if (!RewardedInterstitialAdPreloader.isAdAvailable(id)) return
        if (!done.compareAndSet(false, true)) return
        cleanupPending()
        if (act.isDead()) { fail(AdmErrorType.ACTIVITY_IS_NOT_AVAILABLE); return }
        pollAndShow(act, id)
    }

    private fun failPending(type: AdmErrorType) {
        val done = pendingDone ?: return
        if (!done.compareAndSet(false, true)) return
        cleanupPending()
        fail(type)
    }

    private fun cleanupPending() {
        pendingTimeout?.let { mainHandler.removeCallbacks(it) }
        safeDismiss(pendingDialog)
        pendingActivity = null
        pendingDialog = null
        pendingTimeout = null
        pendingDone = null
    }

    private fun clearPending() {
        pendingDone?.set(true)
        cleanupPending()
    }

    // ========================== Show ============================

    private fun pollAndShow(activity: Activity, id: String) {
        val ad = RewardedInterstitialAdPreloader.pollAd(id)
        if (ad == null) { log("pollAd null ${tag()}"); fail(AdmErrorType.AD_IS_NOT_AVAILABLE); return }
        log("show ${tag()}")
        ad.adEventCallback = this
        ad.show(activity) { rewardItem ->
            log("reward ${tag()}: ${rewardItem.amount} ${rewardItem.type}")
            onReward(rewardItem)
        }
    }

    // ============ RewardedInterstitialAdEventCallback ===========

    override fun onAdShowedFullScreenContent() { log("showed ${tag()}"); onShowed() }
    override fun onAdImpression() { log("impression ${tag()}"); onImpression() }
    override fun onAdClicked() { log("clicked ${tag()}"); onClicked() }
    override fun onAdPaid(value: AdValue) {
        log("paid ${tag()}: ${value.valueMicros} ${value.currencyCode}")
        onPaid(value)
    }

    override fun onAdDismissedFullScreenContent() {
        log("dismissed ${tag()}")
        releasePopup()
        onDismissed()
        onComplete()
    }

    override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
        log("show FAIL ${tag()}: $fullScreenContentError")
        fail(AdmErrorType.AD_IS_NOT_AVAILABLE)
    }

    // ======================== Helpers ===========================

    private fun safeDismiss(dialog: AlertDialog?) {
        dialog ?: return
        runCatching { if (dialog.isShowing) dialog.dismiss() }
    }

    private fun Activity.isDead(): Boolean = isFinishing || isDestroyed

    private fun premiumBlock(): AdmErrorType? {
        val pm = PreferencesManager.getInstance()
        return when {
            pm.isRemoveAds() || pm.isLifetime() -> AdmErrorType.CLIENT_HAVE_BEEN_REMOVED_AD
            pm.isSUB() -> AdmErrorType.CLIENT_HAVE_SUB
            else -> null
        }
    }

    /** Premium -> DỌN buffer preloader đang giữ + báo [onError], trả true (caller return). */
    private fun bailIfPremium(): Boolean {
        val err = premiumBlock() ?: return false
        preloadUnitId?.let { RewardedInterstitialAdPreloader.destroy(it) }
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

    private fun units(): List<String> = AdmConfigAdId.listRewardInterstitialAdUnitID.filter { it.isNotBlank() }

    /** Index kế tiếp (xoay vòng), advance cursor chung mọi instance. */
    private fun nextIndex(size: Int): Int {
        val i = cursor % size
        cursor = (cursor + 1) % size
        return i
    }

    private fun fail(type: AdmErrorType) {
        log("ERROR: $type")
        releasePopup()
        onError(type)
    }

    /** Chiếm mutex full-screen (chặn ad/dialog khác đè). */
    private fun markPopup() {
        GlobalVariables.isShowPopup = true
        popupOwned = true
    }

    /** Nhả mutex nếu instance này đang giữ (không đụng cờ của instance khác). */
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
        const val TAG = "GoogleAds/RewardInter"
        private var cursor = 0   // xoay vòng unit id chung mọi instance
    }
}
