package com.gsx.googleadcompose.GoogleAds.nativead

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.VideoOptions
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.gsx.googleadcompose.GoogleAds.AdCore
import com.gsx.googleadcompose.data.AdmConfigAdId
import com.gsx.googleadcompose.error.AdmErrorType
import com.gsx.googleadcompose.helper.NetworkHelper
import com.gsx.googleadcompose.utils.PreferencesManager

/**
 * Native ad (GMA Next-Gen SDK) theo pattern Google git `NativeComposeFragment`:
 * `NativeAdLoader.load(...)` -> render asset qua [NativeAdView] wrapper (xem [NativeAdCompose]).
 * Layout dựng sẵn ở [NativeAdLayouts]. Load + show luôn như banner. Log tag `GoogleAds/Native`.
 *
 * Controller giữ guard (premium/network/UMP/units), xoay unit id, gắn callback + paid event + màu
 * layout. Unit ID lấy từ [AdmConfigAdId.listNativeAdUnitID]. Cần init MobileAds (UMP) trước.
 *
 * ```
 * AdmNative(Modifier.fillMaxWidth(), layout = NativeLayout.NORMAL) {
 *     backgroundColor = Color(0xFF222222)
 *     ctaColor = Color(0xFFFF6D00)
 *     onError = { type -> Log.w("ad", "$type") }
 * }
 * ```
 */
class AdmNativeAd internal constructor() : NativeAdEventCallback {

    var enableLogging: Boolean = true
    /** Mute video native khi bắt đầu (mặc định true). */
    var startMuted: Boolean = true

    // ---- Màu layout (đọc bởi layout, vd [NativeAdNormalLayout]) ----
    /** Nền card native. */
    var backgroundColor: Color = Color(0xFF1E1330)
    /** Nền nút install (CTA). */
    var ctaColor: Color = Color(0xFF52DE11)
    /** Chữ trên nút install. */
    var ctaTextColor: Color = Color(0xFF062100)
    /** Màu chữ chính (headline). */
    var textColor: Color = Color.White
    /** Màu chữ phụ (body/store/rating). */
    var subTextColor: Color = Color(0xFFB9AED0)

    /** Kênh báo lỗi (xem [AdmErrorType]). */
    var onError: (AdmErrorType) -> Unit = {}

    // ---- Hook sự kiện ----
    /** Ad load xong -> trả [NativeAd] (lấy `.responseInfo` nếu cần). */
    var onLoaded: (NativeAd) -> Unit = {}
    var onImpression: () -> Unit = {}
    var onClicked: () -> Unit = {}
    var onPaid: (AdValue) -> Unit = {}

    private var currentIndex: Int = -1
    private var unitId: String? = null
    private fun tag(): String = "[#$currentIndex ${unitId ?: "-"}]"

    /** Ad đang giữ. */
    @Volatile
    var nativeAd: NativeAd? = null
        private set

    /** True khi premium -> không nên hiện. */
    fun isBlockedByPremium(): Boolean = premiumBlock() != null

    /** Loại lỗi premium (removeAds/lifetime/sub) chặn native, null nếu không bị chặn. */
    fun premiumBlockError(): AdmErrorType? = premiumBlock()

    // ============================ Load ============================

    /**
     * Load 1 native. Kết quả trả qua [onResult] (NativeAd hoặc null) + hook [onLoaded]/[onError].
     * [index] = -1 xoay vòng, >=0 chọn id cụ thể trong [AdmConfigAdId.listNativeAdUnitID].
     */
    fun load(index: Int = -1, onResult: (NativeAd?) -> Unit = {}) =
        loadIds(units(), index, onResult)

    /** Load xoay vòng từ list id [ids] cho trước (vd customIds của [AdmNative]). [index]=-1 xoay vòng. */
    internal fun loadIds(ids: List<String>, index: Int = -1, onResult: (NativeAd?) -> Unit = {}) {
        premiumBlock()?.let { fail(it); onResult(null); return }
        val u = ids.filter { it.isNotBlank() }
        if (u.isEmpty()) { fail(AdmErrorType.LIST_AD_ID_IS_EMPTY); onResult(null); return }
        val idx = if (index < 0) nextIndex(u.size) else index
        val unit = u.getOrNull(idx) ?: run { fail(AdmErrorType.AD_ID_IS_NOT_EXIST); onResult(null); return }
        currentIndex = idx
        loadUnit(unit, onResult)
    }

    /** Load native theo unit id cụ thể (không qua [AdmConfigAdId]). Dùng cho [AdmNativeSequence]. */
    fun loadUnit(unitId: String, onResult: (NativeAd?) -> Unit = {}) {
        envBlock()?.let { fail(it); onResult(null); return }
        if (unitId.isBlank()) { fail(AdmErrorType.AD_ID_IS_NOT_EXIST); onResult(null); return }
        this.unitId = unitId
        log("loadUnit ${tag()}")

        val videoOptions = VideoOptions.Builder().setStartMuted(startMuted).build()
        val request = NativeAdRequest.Builder(unitId, listOf(NativeAd.NativeAdType.NATIVE))
            .setVideoOptions(videoOptions)
            .build()

        NativeAdLoader.load(request, object : NativeAdLoaderCallback {
            override fun onNativeAdLoaded(ad: NativeAd) {
                log("loaded ${tag()}")
                ad.adEventCallback = this@AdmNativeAd
                nativeAd = ad
                onLoaded(ad)
                onResult(ad)
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                log("load FAIL ${tag()}: ${adError.code} ${adError.message}")
                fail(AdmErrorType.AD_IS_NOT_AVAILABLE)
                onResult(null)
            }
        })
    }

    /** Hủy ad (gọi khi rời màn). */
    fun destroy() {
        log("destroy ${tag()}")
        nativeAd?.destroy()
        nativeAd = null
    }

    // ============ NativeAdEventCallback (kế thừa AdEventCallback) ============

    override fun onAdImpression() { log("impression ${tag()}"); onImpression() }
    override fun onAdClicked() { log("clicked ${tag()}"); onClicked() }
    override fun onAdPaid(value: AdValue) {
        log("paid ${tag()}: ${value.valueMicros} ${value.currencyCode}")
        onPaid(value)
    }

    // ======================== Helpers ===========================

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

    private fun units(): List<String> = AdmConfigAdId.listNativeAdUnitID.filter { it.isNotBlank() }

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

    companion object {
        private const val TAG = "GoogleAds/Native"
        private var cursor = 0

        /** True khi premium (removeAds/lifetime/sub) -> không hiện native (kể cả shimmer). */
        fun isPremiumBlocked(): Boolean {
            val pm = PreferencesManager.getInstance()
            return pm.isRemoveAds() || pm.isLifetime() || pm.isSUB()
        }

        /**
         * Preload native — load rồi trả [NativeAd] qua [onResult] để app tự lưu (map/list) + truyền
         * vào [AdmNative]. App SỞ HỮU ad: tự `destroy()` khi xong. Lỗi/premium -> onResult(null).
         *
         * ```
         * AdmNativeAd.preload { ad -> ad?.let { cache["home"] = it } }
         * ```
         */
        fun preload(
            index: Int = -1,
            configure: AdmNativeAd.() -> Unit = {},
            onResult: (NativeAd?) -> Unit,
        ) {
            AdmNativeAd().apply(configure).load(index, onResult = onResult)
        }

        /** Preload native theo unit id cụ thể (cho [AdmNativeSequence]). */
        fun preloadUnit(
            unitId: String,
            configure: AdmNativeAd.() -> Unit = {},
            onResult: (NativeAd?) -> Unit,
        ) {
            AdmNativeAd().apply(configure).loadUnit(unitId, onResult)
        }
    }
}

/**
 * Native tuần tự — preload list id theo thứ tự, luôn giữ sẵn 1 ad cho lần show kế.
 * Show id[n] -> tự preload id[n+1]. Tới id CUỐI thì dừng (KHÔNG xoay vòng). Init list 1 lần.
 *
 * ```
 * AdmNativeSequenceState.init(listOf(0, 1, 2))   // index vào listNativeAdUnitID, 1 lần (Application/Splash)
 * AdmNativeSequence(Modifier.fillMaxWidth())     // mỗi chỗ cần -> show id kế + preload tiếp
 * ```
 */
object AdmNativeSequenceState {
    private const val TAG = "GoogleAds/NativeSeq"

    /** Chờ tối đa preload ad kế (ms) rồi cho đi (isNextReady=true). Đổi được. */
    var timeoutMs: Long = 1000L

    private var ids: List<String> = emptyList()
    private var pos: Int = 0
    private var loading: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    /** Ad sẵn cho lần [consume] kế (state -> Compose observe được). */
    var ready by mutableStateOf<NativeAd?>(null)
        private set

    /**
     * Slot hiện tại ĐÃ LOAD XONG THẬT (ad sẵn HOẶC fail). View consume theo cờ này -> chờ ad thật,
     * KHÔNG bị timeout ép skip. Reset `false` mỗi lần bắt đầu preload id kế.
     */
    var resolved by mutableStateOf(false)
        private set

    /**
     * Cờ NAV (cho nút "Tiếp" onboarding): slot xong THẬT [resolved] HOẶC đã quá [timeoutMs].
     * Timeout chỉ mở nút đi tiếp — KHÔNG ảnh hưởng việc view chờ/hiện ad thật.
     */
    var isNextReady by mutableStateOf(false)
        private set

    /** Hết id (đã tới cuối list, không còn ad sẵn/đang load). */
    val isExhausted: Boolean
        get() = pos >= ids.size && ready == null && !loading

    /**
     * Init bằng list INDEX (vào [AdmConfigAdId.listNativeAdUnitID]) theo thứ tự muốn show, rồi
     * preload id đầu. Index sai/blank bị bỏ. Gọi 1 lần (đổi thứ tự -> gọi lại).
     */
    fun init(indices: List<Int>, timeoutMs: Long = this.timeoutMs) {
        this.timeoutMs = timeoutMs
        val all = AdmConfigAdId.listNativeAdUnitID
        ids = indices.mapNotNull { all.getOrNull(it)?.takeIf { s -> s.isNotBlank() } }
        pos = 0
        handler.removeCallbacksAndMessages(null)
        ready?.destroy()
        ready = null
        loading = false
        resolved = false
        isNextReady = false
        preloadNext()
    }

    private fun preloadNext() {
        handler.removeCallbacksAndMessages(null)
        if (loading) return                                    // đang load -> để callback xử lý
        if (pos >= ids.size) {                                 // hết id -> settled, no ad
            resolved = true
            isNextReady = true
            return
        }
        loading = true
        resolved = false
        isNextReady = false
        // Timeout: CHỈ mở nút đi tiếp (nav). View vẫn chờ ad thật (resolved) -> load xong là hiện.
        handler.postDelayed({ isNextReady = true }, timeoutMs)
        val id = ids[pos]
        Log.d(TAG, "preload pos=$pos $id")
        AdmNativeAd.preloadUnit(id) { ad ->
            loading = false
            handler.removeCallbacksAndMessages(null)
            ready = ad                                         // null nếu fail -> slot rỗng
            resolved = true                                    // load XONG thật (sẵn/fail) -> view consume
            isNextReady = true
            if (ad == null) Log.w(TAG, "preload FAIL pos=$pos (slot rỗng, consume sẽ skip)")
        }
    }

    /**
     * Lấy ad slot hiện tại để show + advance pos + preload id kế. Trả ad (hoặc null nếu fail -> view
     * skip). Chỉ gọi khi [resolved] (đã load xong THẬT). Chưa xong -> null, không advance (view chờ).
     */
    fun consume(): NativeAd? {
        if (!resolved) return null            // slot chưa load xong thật -> view tiếp tục chờ (shimmer)
        val ad = ready                        // null nếu fail -> view skip
        ready = null
        resolved = false
        isNextReady = false
        pos += 1                              // tiến tới id kế; pos == ids.size -> hết
        preloadNext()
        return ad
    }

    /**
     * Dọn buffer khi RỜI HẲN sequence (vd onboarding xong): destroy ad đang giữ chưa consume + dừng
     * preload. Tránh leak ad không view nào lấy. Ad đã consume (view đang show) do view tự destroy.
     */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        ready?.destroy()
        ready = null
        resolved = false
        isNextReady = false
        loading = false
        pos = 0
        ids = emptyList()
    }
}

/**
 * Compose native tuần tự — show ad đang sẵn của [AdmNativeSequenceState], tự kích preload id kế.
 * Cần [AdmNativeSequenceState.init] trước. Chưa sẵn -> shimmer. Ad consumed thuộc view này -> tự destroy.
 *
 * [active]: CHỈ consume khi view thật sự đang hiển thị. Trong Pager (compose sẵn neighbor) -> truyền
 * `active = (page == pager.currentPage)` để KHÔNG consume sớm cho trang off-screen (load tuần tự
 * đúng theo lúc lướt tới, tránh cascade).
 *
 * ```
 * AdmNativeSequence(Modifier.fillMaxWidth(), active = page == pager.currentPage) { ctaColor = ... }
 * ```
 */
@Composable
fun AdmNativeSequence(
    modifier: Modifier = Modifier,
    layout: NativeLayout = NativeLayout.NORMAL,
    active: Boolean = true,
    configure: AdmNativeAd.() -> Unit = {},
) {
    if (AdmNativeAd.isPremiumBlocked()) return            // premium -> render rỗng ngay, KHÔNG shimmer (tránh nháy)

    val resolved = AdmNativeSequenceState.resolved       // observe: slot load XONG thật (sẵn hoặc fail)
    var consumed by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<NativeAd?>(null) }

    // Chỉ consume khi: view đang hiển thị ([active]) + slot load XONG thật ([resolved]).
    LaunchedEffect(resolved, active) {
        if (!consumed && active && resolved) {
            consumed = true
            shown = AdmNativeSequenceState.consume()
        }
    }
    DisposableEffect(Unit) { onDispose { shown?.destroy() } }   // view sở hữu ad đã consume

    when {
        shown != null -> AdmNative(modifier = modifier, layout = layout, nativeAd = shown, configure = configure)
        consumed -> Unit                                    // đã consume nhưng fail -> skip (rỗng)
        else -> AdShimmerNative(modifier, layout)           // chờ ad thật -> shimmer
    }
}

/**
 * Compose native — 2 chế độ tách bằng [index]:
 * - Truyền [nativeAd] (preload) -> show ngay, KHÔNG tự load/destroy (app sở hữu ad).
 * - [index] != null (và không có [nativeAd]) -> TỰ load slot đó (-1 xoay vòng, >=0 cụ thể), shimmer
 *   lúc chờ, tự destroy khi rời màn.
 * - [index] == null + [nativeAd] == null -> SKIP (render rỗng). Dùng cho preload đang chờ.
 *
 * Premium/offline/UMP chưa sẵn -> render rỗng (báo qua [configure].onError).
 *
 * ```
 * AdmNative(Modifier.fillMaxWidth(), nativeAd = cache["home"])      // preload -> show, null -> skip
 * AdmNative(Modifier.fillMaxWidth(), index = -1) { ctaColor = ... } // tự load (xoay vòng)
 * ```
 */
@Composable
fun AdmNative(
    modifier: Modifier = Modifier,
    layout: NativeLayout = NativeLayout.NORMAL,
    nativeAd: NativeAd? = null,
    index: Int? = null,
    customIds: List<String>? = null,
    configure: AdmNativeAd.() -> Unit = {},
) {
    val controller = remember { AdmNativeAd() }
    controller.apply(configure)
    val blocked = remember { controller.isBlockedByPremium() }
    val isPreview = LocalInspectionMode.current

    var selfAd by remember { mutableStateOf<NativeAd?>(null) }
    var failed by remember { mutableStateOf(false) }
    val wantSelf = index != null || customIds != null   // customIds truyền -> xoay vòng list đó

    // Tự load CHỈ khi: không có ad preload + có yêu cầu self-load ([index] != null).
    LaunchedEffect(nativeAd, index, customIds, blocked) {
        if (isPreview) return@LaunchedEffect
        if (blocked) {
            // Premium/removeAds -> báo error (không im lặng), rồi render rỗng.
            controller.onError(controller.premiumBlockError() ?: AdmErrorType.CLIENT_HAVE_BEEN_REMOVED_AD)
            return@LaunchedEffect
        }
        if (nativeAd != null || !wantSelf) return@LaunchedEffect
        val cb: (NativeAd?) -> Unit = { result -> selfAd = result; failed = result == null }
        if (customIds != null) controller.loadIds(customIds, index ?: -1, cb)   // xoay vòng list truyền
        else controller.load(index ?: -1, cb)
    }
    // Chỉ destroy ad TỰ load (controller.nativeAd); ad preload do app sở hữu -> không đụng.
    DisposableEffect(Unit) { onDispose { controller.destroy() } }

    val shown = nativeAd ?: selfAd
    LaunchedEffect(shown) { shown?.adEventCallback = controller }   // gắn impression/click/paid

    when {
        blocked -> Unit
        shown != null -> NativeLayoutRender(shown, layout, controller, modifier)
        failed -> Unit                                              // lỗi -> rỗng (đã báo onError)
        wantSelf -> AdShimmerNative(modifier, layout, controller.backgroundColor)  // self-load chờ
        else -> Unit                                                // preload chờ/skip -> rỗng
    }
}
