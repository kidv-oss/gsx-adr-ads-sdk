package com.gsx.googleadcompose.GoogleIAP

/** Phân loại lỗi billing, map từ Play BillingResponseCode + lỗi nội bộ. */
enum class BillingErrorType {
    CLIENT_NOT_READY,
    PRODUCT_NOT_EXIST,
    USER_CANCELED,
    SERVICE_UNAVAILABLE,
    BILLING_UNAVAILABLE,
    ITEM_UNAVAILABLE,
    DEVELOPER_ERROR,
    ITEM_ALREADY_OWNED,
    ITEM_NOT_OWNED,
    ACKNOWLEDGE_ERROR,
    CONSUME_ERROR,
    FETCH_ERROR,
    NETWORK_ERROR,
    ERROR,
}
