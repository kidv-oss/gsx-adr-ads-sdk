package com.gsx.googleadcompose.GoogleIAP

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.gsx.googleadcompose.GoogleConsent.findActivity

/**
 * Các hành động mua/restore/giá gắn với Activity hiện tại — gọi từ Compose mà không cần truyền
 * Activity (`launchBillingFlow` bắt buộc cần Activity; hàm này tự lấy an toàn từ context Compose).
 *
 * ```
 * val billing = BillingAction()
 * Button(onClick = { billing.purchase(NonConsumableProductId.Lifetime.id) }) { Text("Buy") }
 * ```
 */
@Composable
fun BillingAction(): ComposeBilling {
    val context = LocalContext.current
    return remember(context) { ComposeBilling { context.findActivity() } }
}

class ComposeBilling internal constructor(private val activity: () -> Activity?) {
    fun purchase(productId: String, offerToken: String? = null) =
        activity()?.let { BillingClient.purchase(it, productId, offerToken) }
    fun subscribe(productId: String, offerToken: String? = null) =
        activity()?.let { BillingClient.subscribe(it, productId, offerToken) }
    fun changeSubscription(newProductId: String, offerToken: String? = null) =
        activity()?.let { BillingClient.changeSubscription(it, newProductId, offerToken) }
    fun restore() = BillingClient.restorePurchases(notify = true)
    fun getOffers(productId: String) = BillingClient.getOffers(productId)
    fun getPrice(productId: String): String? = BillingClient.getPrice(productId)
    fun hasFreeTrial(productId: String): Boolean = BillingClient.hasFreeTrial(productId)
    fun getFreeTrialPeriod(productId: String): String? = BillingClient.getFreeTrialPeriod(productId)
    fun getOfferPricing(productId: String): List<OfferPricing> = BillingClient.getOfferPricing(productId)
}

/** Trạng thái premium dưới dạng Compose [State] (lifetime || sub || removeAds). */
@Composable
fun rememberPremiumState(): State<Boolean> = BillingClient.premium.collectAsState()

/**
 * Đăng ký nhận billing event theo vòng đời của composable này — tự đăng ký khi vào, tự gỡ khi ra.
 * Không cần key, không cần Activity.
 */
@Composable
fun BillingEvents(
    onBillingReady: () -> Unit = {},
    onProductsFetched: (List<ProductDetails>) -> Unit = {},
    onPurchased: (List<String>) -> Unit = {},
    onPending: (List<String>) -> Unit = {},
    onAcknowledged: (String) -> Unit = {},
    onConsumed: (String, Int) -> Unit = { _, _ -> },
    onError: (BillingErrorType, BillingResult?) -> Unit = { _, _ -> },
    onRestoreFinished: (Boolean) -> Unit = {},
) {
    // giữ lambda mới nhất, không đăng ký lại listener mỗi lần recompose
    val ready = rememberUpdatedState(onBillingReady)
    val fetched = rememberUpdatedState(onProductsFetched)
    val purchased = rememberUpdatedState(onPurchased)
    val pending = rememberUpdatedState(onPending)
    val acked = rememberUpdatedState(onAcknowledged)
    val consumed = rememberUpdatedState(onConsumed)
    val error = rememberUpdatedState(onError)
    val restored = rememberUpdatedState(onRestoreFinished)
    DisposableEffect(Unit) {
        val listener = object : BillingListener {
            override fun onBillingReady() = ready.value()
            override fun onProductsFetched(products: List<ProductDetails>) = fetched.value(products)
            override fun onPurchased(productIds: List<String>) = purchased.value(productIds)
            override fun onPending(productIds: List<String>) = pending.value(productIds)
            override fun onAcknowledged(productId: String) = acked.value(productId)
            override fun onConsumed(productId: String, quantity: Int) = consumed.value(productId, quantity)
            override fun onBillingError(type: BillingErrorType, result: BillingResult?) = error.value(type, result)
            override fun onRestoreFinished(hasPurchases: Boolean) = restored.value(hasPurchases)
        }
        BillingClient.addListener(listener)
        onDispose { BillingClient.removeListener(listener) }
    }
}
