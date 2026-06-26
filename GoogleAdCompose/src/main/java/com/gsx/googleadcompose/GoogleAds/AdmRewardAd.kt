package com.gsx.googleadcompose.GoogleAds

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.gsx.googleadcompose.GlobalVariables
import com.gsx.googleadcompose.data.AdmConfigAdId
import com.gsx.googleadcompose.error.AdmErrorType
import com.gsx.googleadcompose.helper.DialogHelper
import com.gsx.googleadcompose.helper.NetworkHelper
import com.gsx.googleadcompose.utils.PreferencesManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rewarded ad (GMA Next-Gen SDK) — **one-shot** [RewardedAd.load]: load đúng 1 ad, giữ sẵn, show 1
 * lần rồi bỏ. KHÔNG dùng preloader (preloader tự refill cái kế -> reward show on-demand 1 lần -> refill
 * không bao giờ show -> phí matched request -> tụt show rate). Log tag `GoogleAds/Reward`.
 *
 * [load] = load sẵn 1 ad (gọi sớm). [show]:
 * - Ad đã sẵn -> show ngay (sau dialog [showDelayMs]).
 * - Chưa kịp load -> dialog loading, chờ load xong tự show (timeout [loadTimeoutMs] thì hủy + [onError]).
 *
 * ```
 * val reward = AdmReward {           // composable helper -> trả [AdmRewardAd], tự destroy
 *     onReward = { item -> grant(item.amount) }
 *     onError  = { type -> toast(type) }
 * }
 * reward.load()              // load sẵn 1 ad (vd onCreate)
 * reward.show(activity)      // sẵn -> show; chưa -> dialog chờ rồi show
 * reward.destroy()           // rời màn
 * ```
 *
 * Show xong -> ad bị bỏ, KHÔNG tự load cái kế (no refill). Cần show lại -> gọi [load]/[show] lần nữa.
 * Unit ID lấy từ [AdmConfigAdId.listRewardAdUnitID] (nhiều ID -> xoay vòng mỗi lần [load]).
 * Cần init MobileAds (UMP) trước.
 */
class AdmRewardAd internal constructor() : RewardedAdEventCallback {

    var enableLogging: Boolean = true
    var loadingText: String = "Loading..."
    /** Show khi ad chưa sẵn -> chờ tối đa ngần này rồi hủy. */
    var loadTimeoutMs: Long = 12_000L
    /** Ad đã sẵn vẫn hiện dialog ngần này trước khi show (cho mượt). */
    var showDelayMs: Long = 2_000L

    /** Kênh báo lỗi duy nhất (xem [AdmErrorType]). */
    var onError: (AdmErrorType) -> Unit = {}

    // ---- Hook sự kiện cho app ----
    var onReward: (RewardItem) -> Unit = {}
    /** Ad load xong (sẵn để show) -> trả [ResponseInfo]. */
    var onAvailable: (ResponseInfo) -> Unit = {}
    var onShowed: () -> Unit = {}
    var onImpression: () -> Unit = {}
    var onClicked: () -> Unit = {}
    var onPaid: (AdValue) -> Unit = {}   // doanh thu (paid event)
    var onDismissed: () -> Unit = {}
    var onComplete: () -> Unit = {}

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Ad đã load xong, giữ sẵn để show (null = chưa có). */
    private var loadedAd: RewardedAd? = null
    private var loading: Boolean = false
    private var currentUnit: String? = null
    private var currentIndex: Int = -1
    private fun tag(): String = "[#$currentIndex ${currentUnit ?: "-"}]"

    // ---- Trạng thái "show đang chờ ad load" ----
    private var pendingActivity: Activity? = null
    private var pendingDialog: AlertDialog? = null
    private var pendingDone: AtomicBoolean? = null
    private var pendingTimeout: Runnable? = null
    private var popupOwned = false   // instance này đang giữ mutex isShowPopup

    /** True khi đã có ad sẵn để show ngay. */
    val isReady: Boolean
        get() = loadedAd != null

    // ============================ API ============================

    /**
     * Load sẵn 1 ad (gọi sớm). [index] = -1 -> xoay vòng unit id kế tiếp; >=0 -> chọn cụ thể id thứ
     * [index] trong [AdmConfigAdId.listRewardAdUnitID]. Đã có ad / đang load -> bỏ qua.
     */
    fun load(index: Int = -1, customIds: List<String>? = null) {
        if (bailIfPremium()) return                 // premium -> bỏ ad + onError
        loadError()?.let { fail(it); return }
        if (loadedAd != null || loading) return     // đã có / đang load -> bỏ qua

        // customIds != null -> xoay vòng list đó thay cho AdmConfigAdId.listRewardAdUnitID.
        val u = customIds?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: units()
        val idx = if (index < 0) nextIndex(u.size) else index
        val unit = u.getOrNull(idx) ?: run { fail(AdmErrorType.AD_ID_IS_NOT_EXIST); return }
        currentUnit = unit
        currentIndex = idx
        loading = true
        log("load ${tag()}${if (customIds != null) " [custom]" else ""}")

        RewardedAd.load(AdRequest.Builder(unit).build(), object : AdLoadCallback<RewardedAd> {
            override fun onAdLoaded(ad: RewardedAd) {
                loading = false
                ad.adEventCallback = this@AdmRewardAd
                loadedAd = ad
                log("loaded ${tag()}")
                ad.getResponseInfo()?.let { onAvailable(it) }
                showPendingIfAny()   // có show đang chờ -> show luôn
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                loading = false
                currentUnit = null
                log("load FAIL ${tag()}: ${adError.code} ${adError.message}")
                failPending(AdmErrorType.AD_IS_NOT_AVAILABLE)
            }
        })
    }

    /**
     * Show dùng activity foreground tự lấy ([AdCore.currentActivity]). Không có -> [onError].
     * [index]: chỉ dùng khi CHƯA load -> load id đó rồi show (xem [show]).
     */
    fun show(index: Int = -1, customIds: List<String>? = null) {
        val act = AdCore.currentActivity ?: run { fail(AdmErrorType.ACTIVITY_IS_NOT_AVAILABLE); return }
        show(act, index, customIds)
    }

    /**
     * Show ad. Sẵn -> dialog showDelayMs rồi show. Chưa kịp load -> load id theo [index] + dialog chờ.
     * [index] = -1 xoay vòng, >=0 chọn id cụ thể (CHỈ khi chưa có ad; đã load id khác thì show id đang
     * có). Đang có 1 show chờ -> bỏ qua (double-tap). Lỗi -> [onError].
     */
    fun show(activity: Activity, index: Int = -1, customIds: List<String>? = null) {
        if (activity.isDead()) { fail(AdmErrorType.ACTIVITY_IS_NOT_AVAILABLE); return }
        premiumBlock()?.let { fail(it); return }
        if (pendingActivity != null) { log("đang chờ show, bỏ qua (double-tap)"); return }
        if (GlobalVariables.isShowPopup) { log("full-screen/dialog khác đang hiện, bỏ qua"); return }

        if (loadedAd != null) { showWithDelay(activity); return }

        loadError()?.let { fail(it); return }
        if (loadedAd == null && !loading) load(index, customIds)   // chưa load -> load+show
        waitForAd(activity)
    }

    /** Ad đã sẵn: hiện dialog [showDelayMs] rồi show. */
    private fun showWithDelay(activity: Activity) {
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
                else pollAndShow(activity)
            }
        }
        pendingTimeout = run
        mainHandler.postDelayed(run, showDelayMs)
    }

    /** Bỏ ad đang giữ + hủy show đang chờ (gọi khi rời màn). */
    fun destroy() {
        log("destroy ${tag()}")
        clearPending()
        releasePopup()
        loadedAd?.destroy()
        loadedAd = null
        currentUnit = null
        loading = false
    }

    // ===================== Show chờ ad load ======================

    private fun waitForAd(activity: Activity) {
        log("chờ ad... ${tag()}")
        markPopup()
        pendingActivity = activity
        pendingDialog = DialogHelper.showLoading(activity, loadingText)
        val done = AtomicBoolean(false)
        pendingDone = done
        val timeout = Runnable {
            if (done.compareAndSet(false, true)) {
                log("timeout chờ ad ${tag()}")
                cleanupPending()
                fail(AdmErrorType.AD_IS_NOT_AVAILABLE)
            }
        }
        pendingTimeout = timeout
        mainHandler.postDelayed(timeout, loadTimeoutMs)
    }

    private fun showPendingIfAny() {
        val act = pendingActivity ?: return
        if (loadedAd == null) return
        val done = pendingDone ?: return
        if (!done.compareAndSet(false, true)) return
        cleanupPending()
        if (act.isDead()) { fail(AdmErrorType.ACTIVITY_IS_NOT_AVAILABLE); return }
        pollAndShow(act)
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

    private fun pollAndShow(activity: Activity) {
        val ad = loadedAd ?: run { log("ad null ${tag()}"); fail(AdmErrorType.AD_IS_NOT_AVAILABLE); return }
        log("show ${tag()}")
        loadedAd = null   // consume -> KHÔNG refill (one-shot)
        ad.show(activity) { rewardItem ->
            log("reward ${tag()}: ${rewardItem.amount} ${rewardItem.type}")
            onReward(rewardItem)
        }
    }

    // ================== RewardedAdEventCallback ==================

    override fun onAdShowedFullScreenContent() { log("showed ${tag()}"); onShowed() }
    override fun onAdImpression() { onImpression() }
    override fun onAdClicked() { onClicked() }
    override fun onAdPaid(value: AdValue) { onPaid(value) }

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

    /** Premium -> bỏ qua ad (sub / remove-ads / lifetime). */
    private fun premiumBlock(): AdmErrorType? {
        val pm = PreferencesManager.getInstance()
        return when {
            pm.isRemoveAds() || pm.isLifetime() -> AdmErrorType.CLIENT_HAVE_BEEN_REMOVED_AD
            pm.isSUB() -> AdmErrorType.CLIENT_HAVE_SUB
            else -> null
        }
    }

    /** Premium -> bỏ ad đang giữ + báo [onError], trả true (caller return). */
    private fun bailIfPremium(): Boolean {
        val err = premiumBlock() ?: return false
        loadedAd?.destroy()
        loadedAd = null
        currentUnit = null
        fail(err)
        return true
    }

    private fun loadError(): AdmErrorType? {
        premiumBlock()?.let { return it }
        if (!AdCore.isMobileAdsReady) return AdmErrorType.UMP_IS_NOT_ACTIVE
        val ctx = AdCore.appContext
        if (ctx != null && !NetworkHelper.isOnline(ctx)) return AdmErrorType.NETWORK_IS_NOT_AVAILABLE
        if (units().isEmpty()) return AdmErrorType.LIST_AD_ID_IS_EMPTY
        return null
    }

    private fun units(): List<String> = AdmConfigAdId.listRewardAdUnitID.filter { it.isNotBlank() }

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
        const val TAG = "GoogleAds/Reward"
        private var cursor = 0   // xoay vòng unit id chung mọi instance
    }
}
