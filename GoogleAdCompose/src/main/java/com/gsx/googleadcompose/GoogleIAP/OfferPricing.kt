package com.gsx.googleadcompose.GoogleIAP

/**
 * Pricing 1 offer của subscription. Build từ [BillingClient.getOfferPricing].
 *
 * @property basePriceMicros giá gốc (micros) — phase định kỳ chính.
 * @property basePrice       giá gốc đã format theo locale (vd "199.000₫").
 * @property salePriceMicros giá sau discount (micros), null nếu offer không có phase intro.
 * @property salePrice       giá sau discount đã format, null nếu không giảm.
 * @property discountPercent % giảm (0 nếu không có discount). Tính = (1 - sale/base) * 100.
 * @property hasFreeTrial    offer có phase free-trial (giá 0) không.
 * @property trialPeriod     thời gian trial ISO-8601 (vd "P3D"), null nếu không có.
 * @property billingPeriod   chu kỳ thanh toán của phase base ISO-8601 (vd "P1M", "P1Y").
 * @property currencyCode    mã tiền tệ ISO-4217 (vd "VND", "USD") — dùng để tự format giá micros.
 */
data class OfferPricing(
    val productId: String,
    val offerToken: String,
    val basePriceMicros: Long,
    val basePrice: String,
    val salePriceMicros: Long?,
    val salePrice: String?,
    val discountPercent: Int,
    val hasFreeTrial: Boolean,
    val trialPeriod: String?,
    val billingPeriod: String,
    val currencyCode: String,
) {
    /** True nếu offer có giảm giá (có phase intro rẻ hơn base). */
    val hasDiscount: Boolean get() = discountPercent > 0

    /** Giá hiển thị: ưu tiên giá sale nếu có, không thì giá gốc. */
    val displayPrice: String get() = salePrice ?: basePrice
}
