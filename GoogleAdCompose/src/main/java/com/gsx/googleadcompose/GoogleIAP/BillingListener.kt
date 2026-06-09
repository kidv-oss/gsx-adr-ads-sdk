package com.gsx.googleadcompose.GoogleIAP

import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails

/** Callback billing. Mọi hàm đều tùy chọn (mặc định không làm gì). */
interface BillingListener {
    /** Billing client đã connect và đã fetch product details. */
    fun onBillingReady() {}

    /** Đã fetch product details từ Play. */
    fun onProductsFetched(products: List<ProductDetails>) {}

    /** Một purchase đã xác nhận (trạng thái PURCHASED). [productIds] là các product trong đó. */
    fun onPurchased(productIds: List<String>) {}

    /**
     * Purchase đang PENDING (vd tiền mặt/chờ thanh toán). KHÔNG cấp quyền — Google chỉ cấp khi
     * chuyển sang PURCHASED. Dùng để nhắc user hoàn tất thanh toán.
     */
    fun onPending(productIds: List<String>) {}

    /** Một purchase non-consumable / subscription đã được acknowledge. */
    fun onAcknowledged(productId: String) {}

    /**
     * Một purchase consumable đã được consume — cấp item ngay lúc này.
     * @param quantity số lượng mua trong giao dịch (nhân lên khi cấp, tránh giao thiếu).
     */
    fun onConsumed(productId: String, quantity: Int) {}

    /** Mọi lỗi billing. [result] là null với lỗi nội bộ (không phải từ Play). */
    fun onBillingError(type: BillingErrorType, result: BillingResult?) {}

    /**
     * Restore THỦ CÔNG (từ nút) hoàn tất. [hasPurchases] = user có sở hữu gì để khôi phục không.
     * Không bắn khi restore tự động lúc foreground.
     */
    fun onRestoreFinished(hasPurchases: Boolean) {}
}
