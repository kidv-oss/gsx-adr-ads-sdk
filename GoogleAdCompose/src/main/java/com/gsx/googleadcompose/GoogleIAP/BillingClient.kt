package com.gsx.googleadcompose.GoogleIAP

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient as PlayBillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.gsx.googleadcompose.GlobalVariables
import com.gsx.googleadcompose.helper.DialogHelper
import com.gsx.googleadcompose.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Play Billing (v9) — object dùng toàn app. Gọi [init] 1 lần trong Application, sau đó gọi
 * trực tiếp ở bất kỳ đâu: `BillingClient.purchase(activity, id)`. Ưu tiên Compose; trong UI dùng
 * [BillingAction] / [BillingEvents] / [rememberPremiumState].
 *
 * Quyền (entitlement) lưu bằng 2 cờ boolean (sống qua khởi động lại / consume):
 * - [isSub] — có subscription HOẶC lifetime (non-consumable mua 1 lần). Cả hai gộp chung.
 * - [isRemoveAds] — cờ do app tự định nghĩa.
 *
 * Bước sau khi mua chọn theo product ID thuộc list nào: consumable -> consume, còn lại -> acknowledge.
 */
object BillingClient {

    var enableLogging: Boolean = false

    /**
     * Tùy chọn: ID tài khoản user đã hash (không phải email/PII), gắn vào mỗi giao dịch để Google
     * phát hiện gian lận. Set sau khi user đăng nhập.
     */
    var obfuscatedAccountId: String? = null

    private lateinit var billingClient: PlayBillingClient
    private lateinit var scope: CoroutineScope

    private var consumableIds: List<String> = emptyList()
    private var nonConsumableIds: List<String> = emptyList()
    private var subscriptionIds: List<String> = emptyList()
    private val inAppIds: List<String> get() = consumableIds + nonConsumableIds

    private val productDetailsMap = mutableMapOf<String, ProductDetails>()
    private val isFetching = AtomicBoolean(false)
    private var initialized = false

    // Token của sub đang active, dùng cho upgrade/downgrade.
    @Volatile private var currentSubToken: String? = null

    @Volatile private var _productsFetched = false
    /** True sau khi fetch product details thành công ít nhất 1 lần (dùng cho splash). */
    val isProductsFetched: Boolean get() = _productsFetched

    private val listeners = CopyOnWriteArrayList<BillingListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Activity foreground gần nhất (weak — không bao giờ giữ strong Activity trong object static).
    private var activityRef: WeakReference<Activity>? = null
    private val currentActivity: Activity? get() = activityRef?.get()

    private val _premium = MutableStateFlow(false)
    /** True when the user owns lifetime, a subscription, or remove-ads. Reactive. */
    val premium: StateFlow<Boolean> = _premium.asStateFlow()

    val isInitialized: Boolean get() = initialized

    /**
     * Khởi tạo 1 lần (Application.onCreate). Gọi nhiều lần cũng an toàn.
     *
     * @param autoRefreshOnForeground true (mặc định): tự connect lần đầu lên foreground và tự
     *   query lại purchase mỗi khi app trở lại foreground — app KHÔNG cần tự gọi
     *   [startConnection]/[restorePurchases]. Yêu cầu [init] chạy trên main thread (onCreate là main).
     */
    fun init(
        context: Context,
        consumableIds: List<String> = emptyList(),
        nonConsumableIds: List<String> = emptyList(),
        subscriptionIds: List<String> = emptyList(),
        listener: BillingListener? = null,
        autoRefreshOnForeground: Boolean = true,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    ) {
        if (initialized) return
        // Mỗi product ID chỉ được xuất hiện 1 lần (trong và giữa các list) để route đúng loại.
        val allIds = consumableIds + nonConsumableIds + subscriptionIds
        require(allIds.size == allIds.distinct().size) {
            "Product ID bị trùng trong/giữa các list — mỗi ID chỉ được xuất hiện một lần."
        }
        this.consumableIds = consumableIds
        this.nonConsumableIds = nonConsumableIds
        this.subscriptionIds = subscriptionIds
        this.scope = scope
        // Prefs đã auto-init qua GoogleAdsComposeStartUp (trước Application.onCreate) — không cần gọi ở đây.
        listener?.let { listeners.addIfAbsent(it) }

        billingClient = PlayBillingClient.newBuilder(context.applicationContext)
            .setListener(purchasesListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .enableAutoServiceReconnection()
            .build()

        _premium.value = isPremium()
        initialized = true
        log("init[v2]: consumable=$consumableIds non=$nonConsumableIds subs=$subscriptionIds premium=${isPremium()}")

        if (autoRefreshOnForeground) {
            (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(activityCallbacks)
            ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        }
    }

    // Connect lần đầu foreground + query lại purchase mỗi lần foreground (theo khuyến nghị Google).
    // Check mạng trước; offline thì hiện dialog retry (nếu có Activity), rồi mới fetch.
    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (!initialized) return
            val activity = currentActivity
            if (activity != null) {
                DialogHelper.requireNetwork(activity) { connectOrRefresh() }
            } else {
                connectOrRefresh()
            }
        }
    }

    private fun connectOrRefresh() {
        log("foreground: ready=${billingClient.isReady}")
        if (billingClient.isReady) onConnected() else startConnection()
    }

    // Chạy mỗi khi client ready: fetch product + restore. queryProducts tự guard không chạy trùng.
    private fun onConnected(onReady: () -> Unit = {}) {
        queryProducts()
        restorePurchases()
        dispatch { it.onBillingReady() }
        onReady()
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) { activityRef = WeakReference(activity) }
        override fun onActivityResumed(activity: Activity) { activityRef = WeakReference(activity) }
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            if (currentActivity === activity) activityRef = null
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivity === activity) activityRef = null
        }
    }

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            PlayBillingClient.BillingResponseCode.OK -> purchases?.forEach { handlePurchase(it) }
            // Đã sở hữu (vd cài lại, mua lại non-consumable): reconcile bằng restore để cấp quyền.
            PlayBillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                log("purchase update: ITEM_ALREADY_OWNED -> reconcile bằng restore")
                restorePurchases()
                dispatch { it.onBillingError(BillingErrorType.ITEM_ALREADY_OWNED, result) }
            }
            else -> {
                log("purchase update lỗi: ${result.responseCode} ${result.debugMessage}")
                dispatch { it.onBillingError(result.responseCode.toErrorType(), result) }
            }
        }
    }

    // ---- Listeners --------------------------------------------------------------------------

    /** Đăng ký listener. Trong Compose nên dùng composable [BillingEvents]. */
    fun addListener(listener: BillingListener) { listeners.addIfAbsent(listener) }

    /** Hủy đăng ký listener. */
    fun removeListener(listener: BillingListener) { listeners.remove(listener) }

    private fun dispatch(block: (BillingListener) -> Unit) {
        mainHandler.post { listeners.forEach(block) }
    }

    // ---- Entitlement flags (persisted) ------------------------------------------------------

    // Source of truth = PreferencesManager (Prefs "app_preferences"). BillingClient chỉ GHI (Play
    // quản purchase/restore); ĐỌC quyền thì dùng thẳng PreferencesManager.getInstance() ở app:
    //   PreferencesManager.getInstance().isSUB()        // đã mua sub/lifetime
    //   PreferencesManager.getInstance().isRemoveAds()  // remove-ads
    // Lifetime (non-consumable) gộp chung vào cờ sub — app coi cả hai như nhau.

    // Premium tổng (sub/lifetime hoặc remove-ads) — chỉ dùng nội bộ cho StateFlow [premium].
    // Prefs auto-init qua GoogleAdsComposeStartUp nên đọc được kể cả trước BillingClient.init.
    private fun isPremium(): Boolean = PreferencesManager.getInstance().let {
        it.isSUB() || it.isRemoveAds()
    }

    // sub do Play quản (handlePurchase/restore) -> private. productId = gói sở hữu (để hiện tên).
    private fun setSub(value: Boolean, productId: String? = null) {
        if (!initialized) return
        val pm = PreferencesManager.getInstance()
        if (value) {
            pm.purchaseAndRestoreSuccess()
            pm.setOwnedProduct(productId)
        } else {
            pm.purchaseFailed()
            pm.setOwnedProduct(null)
        }
        log("setSub: sub=$value owned=${pm.ownedProduct()}")
        refreshPremium()
    }

    /** Cờ remove-ads do app tự định nghĩa (vd reward/promo). Có lưu lại. */
    fun setRemoveAds(value: Boolean) {
        if (!initialized) return
        PreferencesManager.getInstance().removeAds(value)
        refreshPremium()
    }

    private fun refreshPremium() {
        val premium = isPremium()
        _premium.value = premium

        // Premium -> ngừng hiện màn subscription và app-open ad.
        if (premium) {
            GlobalVariables.isShowSub = false
            GlobalVariables.canShowOpenAd = false
        } else {
            GlobalVariables.isShowSub = true
        }
    }

    // ---- Products ---------------------------------------------------------------------------

    fun isReady(): Boolean = initialized && billingClient.isReady
    fun getProducts(): List<ProductDetails> = productDetailsMap.values.toList()
    fun getProduct(productId: String): ProductDetails? = productDetailsMap[productId]

    /** Tất cả offer Google trả cho 1 subscription (đã lọc theo eligibility). Rỗng nếu không phải sub / chưa fetch. */
    fun getOffers(productId: String): List<ProductDetails.SubscriptionOfferDetails> =
        productDetailsMap[productId]?.subscriptionOfferDetails ?: emptyList()

    /**
     * Giá định kỳ đã format theo locale, null nếu chưa fetch. Với subscription trả về phase trả
     * tiền chính (bỏ qua phase free-trial / intro có giá 0).
     */
    fun getPrice(productId: String): String? {
        val details = productDetailsMap[productId] ?: return null
        details.oneTimePurchaseOfferDetails?.formattedPrice?.let { return it }
        val phases = details.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList
        return phases?.lastOrNull { it.priceAmountMicros > 0 }?.formattedPrice
            ?: phases?.lastOrNull()?.formattedPrice
    }

    /** True nếu [productId] (subscription) có phase free-trial / giá 0. */
    fun hasFreeTrial(productId: String): Boolean =
        productDetailsMap[productId]?.subscriptionOfferDetails
            ?.any { offer -> offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
            ?: false

    /**
     * Thời gian free-trial dạng ISO-8601 (vd "P3D", "P1W", "P1M"), null nếu không có trial.
     * Tự format để hiển thị.
     */
    fun getFreeTrialPeriod(productId: String): String? {
        val offers = productDetailsMap[productId]?.subscriptionOfferDetails ?: return null
        offers.forEach { offer ->
            offer.pricingPhases.pricingPhaseList.forEach { phase ->
                if (phase.priceAmountMicros == 0L) return phase.billingPeriod
            }
        }
        return null
    }

    /**
     * Pricing đầy đủ của từng offer (subscription): giá gốc, giá sau discount, % giảm, trial.
     * Mỗi offer trên Play Console -> 1 [OfferPricing]. Rỗng nếu không phải sub / chưa fetch.
     *
     * Trong 1 offer các phase chạy theo thứ tự: trial (giá 0) -> intro/discount (FINITE, giá thấp)
     * -> base định kỳ (INFINITE, giá gốc). % giảm = so phase intro với phase base.
     */
    fun getOfferPricing(productId: String): List<OfferPricing> {
        val offers = productDetailsMap[productId]?.subscriptionOfferDetails ?: return emptyList()
        return offers.map { offer ->
            val phases = offer.pricingPhases.pricingPhaseList

            // Phase định kỳ chính = INFINITE_RECURRING; fallback: phase trả tiền cuối cùng.
            val basePhase = phases.lastOrNull {
                it.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING && it.priceAmountMicros > 0
            } ?: phases.lastOrNull { it.priceAmountMicros > 0 }

            // Phase intro/discount = trả tiền (>0), rẻ hơn base VÀ CÙNG billingPeriod với base.
            // Bắt buộc cùng kỳ: tránh so giá khác chu kỳ (vd 52k/tuần vs 289k/tháng) ra % rác.
            val baseMicros = basePhase?.priceAmountMicros ?: 0L
            val basePeriod = basePhase?.billingPeriod
            val salePhase = phases.firstOrNull {
                it.priceAmountMicros in 1 until baseMicros && it.billingPeriod == basePeriod
            }

            val trialPhase = phases.firstOrNull { it.priceAmountMicros == 0L }
            val saleMicros = salePhase?.priceAmountMicros
            val discount = if (baseMicros > 0 && saleMicros != null && saleMicros < baseMicros)
                ((1 - saleMicros.toDouble() / baseMicros) * 100).toInt()
            else 0

            OfferPricing(
                productId = productId,
                offerToken = offer.offerToken,
                basePriceMicros = baseMicros,
                basePrice = basePhase?.formattedPrice ?: "",
                salePriceMicros = saleMicros,
                salePrice = salePhase?.formattedPrice,
                discountPercent = discount,
                hasFreeTrial = trialPhase != null,
                trialPeriod = trialPhase?.billingPeriod,
                billingPeriod = basePhase?.billingPeriod ?: "",
                currencyCode = basePhase?.priceCurrencyCode ?: "",
            )
        }
    }

    // ---- Connection -------------------------------------------------------------------------

    /** Connect (gọi nhiều lần an toàn). Khi ready: fetch products + restore purchases. */
    fun startConnection(onReady: () -> Unit = {}) {
        requireInit()
        log("startConnection: ready=${billingClient.isReady}")
        if (billingClient.isReady) {
            onConnected(onReady)
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == PlayBillingClient.BillingResponseCode.OK) {
                    log("Billing ready.")
                    onConnected(onReady)
                } else {
                    log("Billing setup FAILED: ${result.responseCode} ${result.debugMessage}")
                    dispatch { it.onBillingError(result.responseCode.toErrorType(), result) }
                }
            }

            override fun onBillingServiceDisconnected() {
                // v9 tự reconnect; không làm gì.
            }
        })
    }

    /**
     * Đảm bảo billing dùng được từ một Activity. Offline -> dialog retry (không hủy được), tự biến
     * mất khi có mạng lại rồi chạy tiếp.
     */
    fun checkBilling(activity: Activity, onReady: () -> Unit = {}) {
        DialogHelper.requireNetwork(activity) { startConnection(onReady) }
    }

    /** Fetch product details cho tất cả ID đã cấu hình, lỗi thì retry tăng dần (exponential backoff). */
    fun queryProducts() {
        requireInit()
        if (!isFetching.compareAndSet(false, true)) {
            log("queryProducts: bỏ qua (đang fetch)")
            return
        }
        log("queryProducts: bắt đầu fetch inApp=$inAppIds subs=$subscriptionIds")
        scope.launch {
            var delayMs = FETCH_RETRY_START_MS
            while (true) {
                if (fetchAll()) {
                    _productsFetched = true
                    dispatch { it.onProductsFetched(getProducts()) }
                    log("Products fetched: ${productDetailsMap.keys}")
                    break
                }
                dispatch { it.onBillingError(BillingErrorType.FETCH_ERROR, null) }
                // Đạt mức cap 15 phút thì dừng, không retry vô hạn (vd product ID sai).
                if (delayMs >= FETCH_RETRY_MAX_MS) {
                    log("Product fetch failed, reached max retry interval — stop.")
                    break
                }
                log("Product fetch failed, retry in ${delayMs}ms")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(FETCH_RETRY_MAX_MS)
            }
            isFetching.set(false)
        }
    }

    private suspend fun fetchAll(): Boolean {
        val inAppOk = queryType(inAppIds, ProductType.INAPP)
        // Bỏ qua SUBS nếu không cấu hình hoặc thiết bị không hỗ trợ (tránh retry vô hạn).
        val subsOk = if (subscriptionIds.isEmpty() || !isSubsSupported()) true
        else queryType(subscriptionIds, ProductType.SUBS)
        return inAppOk && subsOk
    }

    private fun isSubsSupported(): Boolean =
        billingClient.isFeatureSupported(PlayBillingClient.FeatureType.SUBSCRIPTIONS)
            .responseCode == PlayBillingClient.BillingResponseCode.OK

    private suspend fun queryType(ids: List<String>, type: String): Boolean {
        val products = ids.filter { it.isNotBlank() }
        if (products.isEmpty()) return true
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                products.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(type)
                        .build()
                }
            )
            .build()
        val result = runCatching { billingClient.queryProductDetails(params) }.getOrNull()
        if (result == null) {
            log("query $type lỗi: exception")
            return false
        }
        if (result.billingResult.responseCode != PlayBillingClient.BillingResponseCode.OK) {
            log("query $type lỗi: ${result.billingResult.responseCode} ${result.billingResult.debugMessage}")
            return false
        }
        val fetched = result.productDetailsList.orEmpty()
        fetched.forEach { productDetailsMap[it.productId] = it }
        log("query $type OK: yêu cầu=$products, lấy được=${fetched.map { it.productId }}")
        if (enableLogging) fetched.forEach { logOffers(it) }
        if (fetched.size != products.size) {
            log("⚠️ THIẾU product $type: ${products - fetched.map { it.productId }.toSet()} — chưa active/đúng ID trên Play Console, hoặc app chưa lên track testing.")
        }
        return true
    }

    private fun logOffers(details: ProductDetails) {
        details.oneTimePurchaseOfferDetails?.let {
            log("  • ${details.productId} (in-app): ${it.formattedPrice}")
        }
        details.subscriptionOfferDetails?.forEach { offer ->
            val phases = offer.pricingPhases.pricingPhaseList.joinToString(" -> ") { p ->
                "${p.formattedPrice}/${p.billingPeriod}" +
                    (if (p.priceAmountMicros == 0L) "(trial)" else "")
            }
            log("  • ${details.productId} offer[base=${offer.basePlanId} offerId=${offer.offerId}] token=${offer.offerToken.take(12)}… phases=[$phases]")
        }
    }

    // ---- Purchase ---------------------------------------------------------------------------

    /**
     * Mua bất kỳ product (consumable / non-consumable / subscription). Bước sau khi mua tự chọn
     * theo product ID thuộc list nào.
     *
     * @param offerToken (sub) chỉ định offer muốn mua — lấy từ [getOffers]. Null thì tự chọn offer
     *   có free-trial (không có thì offer đầu).
     * @param oldPurchaseToken (sub) token của sub cũ khi upgrade/downgrade — xem [changeSubscription].
     * @param replacementMode chế độ thay thế khi đổi gói (mặc định WITH_TIME_PRORATION).
     */
    fun purchase(
        activity: Activity,
        productId: String,
        offerToken: String? = null,
        oldPurchaseToken: String? = null,
        replacementMode: Int = BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION,
    ): BillingResult? {
        requireInit()
        if (!billingClient.isReady) {
            dispatch { it.onBillingError(BillingErrorType.CLIENT_NOT_READY, null) }
            return null
        }
        val details = productDetailsMap[productId]
        if (details == null) {
            dispatch { it.onBillingError(BillingErrorType.PRODUCT_NOT_EXIST, null) }
            log("purchase: no details for $productId; fetch first")
            return null
        }
        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        // Sub bắt buộc offerToken: dùng offer chỉ định, hoặc tự chọn offer có free-trial / offer đầu.
        details.subscriptionOfferDetails?.let { offers ->
            val token = offerToken ?: (
                offers.firstOrNull { o ->
                    o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
                } ?: offers.first()
                ).offerToken
            paramsBuilder.setOfferToken(token)
        }
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsBuilder.build()))
            .apply {
                obfuscatedAccountId?.let { setObfuscatedAccountId(it) }
                // Upgrade/downgrade: gắn sub cũ để Play tính proration.
                if (oldPurchaseToken != null) {
                    setSubscriptionUpdateParams(
                        BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                            .setOldPurchaseToken(oldPurchaseToken)
                            .setSubscriptionReplacementMode(replacementMode)
                            .build()
                    )
                }
            }
            .build()
        log("purchase: launch $productId (oldToken=${oldPurchaseToken != null})")
        val launch = billingClient.launchBillingFlow(activity, flowParams)
        log("purchase: launch result ${launch.responseCode} ${launch.debugMessage}")
        return launch
    }

    /** Đăng ký subscription. [offerToken] chỉ định offer (lấy từ [getOffers]); null = tự chọn. */
    fun subscribe(activity: Activity, productId: String, offerToken: String? = null): BillingResult? =
        purchase(activity, productId, offerToken)

    /**
     * Upgrade/downgrade subscription hiện tại sang [newProductId]. Tự dùng token sub đang active.
     * Nếu chưa có sub nào -> mua mới như [subscribe]. Sub cũ phải đã acknowledge (lib tự ack khi restore).
     */
    fun changeSubscription(
        activity: Activity,
        newProductId: String,
        offerToken: String? = null,
        replacementMode: Int = BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION,
    ): BillingResult? {
        val oldToken = currentSubToken
            ?: return purchase(activity, newProductId, offerToken)
        return purchase(activity, newProductId, offerToken, oldToken, replacementMode)
    }

    /**
     * Query lại purchase đang sở hữu và cập nhật cờ. Gọi lúc resume hoặc từ nút Restore.
     * @param notify true (restore thủ công từ nút) -> bắn [BillingListener.onRestoreFinished].
     *   Auto-foreground gọi với false để không spam toast.
     */
    fun restorePurchases(notify: Boolean = false) {
        requireInit()
        scope.launch {
            // QUAN TRỌNG: chỉ cập nhật cờ khi query THÀNH CÔNG, nếu không một lỗi mạng/query tạm
            // thời sẽ thu hồi nhầm quyền của user. Lifetime (INAPP non-consumable) gộp vào cờ sub.
            val subs = queryOwned(ProductType.SUBS)
            val inApp = queryOwned(ProductType.INAPP)
            subs?.forEach { acknowledge(it) }
            inApp?.forEach { acknowledge(it) }
            subs?.firstOrNull()?.purchaseToken?.let { currentSubToken = it }

            val ownsSub = subs?.isNotEmpty() == true
            val ownsLifetime = inApp?.any { p -> p.products.any { it in nonConsumableIds } } == true

            // Tất cả gói đang sở hữu (chỉ ID có cấu hình) — để log.
            val ownedPurchases = (subs.orEmpty() + inApp.orEmpty())
                .filter { p -> p.products.any { it in subscriptionIds || it in nonConsumableIds } }
            val ownedIds = ownedPurchases.flatMap { it.products }
                .filter { it in subscriptionIds || it in nonConsumableIds }
            // Gói MỚI NHẤT theo purchaseTime — để hiển thị. Gói hết hạn tự rớt khỏi danh sách.
            val newestId = ownedPurchases.maxByOrNull { it.purchaseTime }
                ?.products?.firstOrNull { it in subscriptionIds || it in nonConsumableIds }

            log("Owned products: $ownedIds")
            when {
                ownsSub || ownsLifetime -> setSub(true, newestId)
                // Chỉ thu hồi khi CẢ HAI query thành công & không sở hữu gì (tránh revoke nhầm khi 1 query lỗi).
                subs != null && inApp != null -> setSub(false)
            }
            val pm = PreferencesManager.getInstance()
            log("Restore done. sub=${pm.isSUB()} owned=${pm.ownedProduct()} notify=$notify")
            if (notify) {
                dispatch { it.onRestoreFinished(pm.isSUB()) }
            }
        }
    }

    /** Trả về purchase ở trạng thái PURCHASED, hoặc null nếu query lỗi (để caller không thu hồi cờ). */
    private suspend fun queryOwned(type: String): List<Purchase>? {
        val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
        val result = runCatching { billingClient.queryPurchasesAsync(params) }.getOrNull()
        if (result == null || result.billingResult.responseCode != PlayBillingClient.BillingResponseCode.OK) {
            dispatch { it.onBillingError(BillingErrorType.FETCH_ERROR, result?.billingResult) }
            return null
        }
        return result.purchasesList.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
    }

    private fun handlePurchase(purchase: Purchase) {
        // CHỈ cấp quyền khi PURCHASED. PENDING (vd thanh toán tiền mặt) không cấp — chỉ báo.
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                log("PENDING: ${purchase.products} — chưa cấp quyền")
                dispatch { it.onPending(purchase.products) }
                return
            }
            Purchase.PurchaseState.PURCHASED -> Unit
            else -> return
        }
        log("PURCHASED: ${purchase.products} qty=${purchase.quantity}")
        dispatch { it.onPurchased(purchase.products) }
        purchase.products.forEach { id ->
            when {
                id in subscriptionIds -> { acknowledge(purchase); setSub(true, id); currentSubToken = purchase.purchaseToken }
                id in consumableIds -> consume(purchase, id)
                else -> { acknowledge(purchase); setSub(true, id) } // non-consumable (lifetime) -> gộp vào sub
            }
        }
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        scope.launch {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            val result = billingClient.acknowledgePurchase(params)
            if (result.responseCode == PlayBillingClient.BillingResponseCode.OK) {
                log("acknowledged: ${purchase.products}")
                dispatch { l -> purchase.products.forEach { l.onAcknowledged(it) } }
            } else {
                log("acknowledge lỗi: ${result.responseCode} ${result.debugMessage}")
                dispatch { it.onBillingError(BillingErrorType.ACKNOWLEDGE_ERROR, result) }
            }
        }
    }

    private fun consume(purchase: Purchase, productId: String) {
        scope.launch {
            val params = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            val result = billingClient.consumePurchase(params)
            if (result.billingResult.responseCode == PlayBillingClient.BillingResponseCode.OK) {
                val qty = purchase.quantity
                log("consumed: $productId x$qty")
                dispatch { it.onConsumed(productId, qty) }
            } else {
                log("consume lỗi: ${result.billingResult.responseCode} ${result.billingResult.debugMessage}")
                dispatch { it.onBillingError(BillingErrorType.CONSUME_ERROR, result.billingResult) }
            }
        }
    }

    fun endConnection() {
        if (initialized) billingClient.endConnection()
    }

    private fun requireInit() {
        check(initialized) { "Phải gọi BillingClient.init() trước (trong Application)." }
    }

    private fun log(msg: String) {
        if (enableLogging) Log.d(TAG, msg)
    }

    private fun Int.toErrorType(): BillingErrorType = when (this) {
        PlayBillingClient.BillingResponseCode.USER_CANCELED -> BillingErrorType.USER_CANCELED
        PlayBillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> BillingErrorType.SERVICE_UNAVAILABLE
        PlayBillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> BillingErrorType.BILLING_UNAVAILABLE
        PlayBillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> BillingErrorType.ITEM_UNAVAILABLE
        PlayBillingClient.BillingResponseCode.DEVELOPER_ERROR -> BillingErrorType.DEVELOPER_ERROR
        PlayBillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> BillingErrorType.ITEM_ALREADY_OWNED
        PlayBillingClient.BillingResponseCode.ITEM_NOT_OWNED -> BillingErrorType.ITEM_NOT_OWNED
        PlayBillingClient.BillingResponseCode.NETWORK_ERROR -> BillingErrorType.NETWORK_ERROR
        else -> BillingErrorType.ERROR
    }

    private const val TAG = "GoogleAds/Billing"
    private const val FETCH_RETRY_START_MS = 1_000L
    private const val FETCH_RETRY_MAX_MS = 15L * 60L * 1000L
}
