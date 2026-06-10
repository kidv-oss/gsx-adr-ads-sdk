package com.compose.gsxgoogleadscompose.premium

// Nguồn DUY NHẤT cho product ID — dùng cho cả BillingClient.init (App) lẫn UI (PremiumScreen).
// applicationId = "anime.girlfriend.app"
object MyProducts {
    const val MONTHLY = "anime.girlfriend.app.monthly"
    const val YEARLY = "anime.girlfriend.app.yearly1"
    const val LIFETIME = "lifetime"

    val subscriptions = listOf(YEARLY, MONTHLY)
    val nonConsumable = listOf(LIFETIME)

    val all = listOf(MONTHLY, YEARLY, LIFETIME)

    fun title(id: String) = when (id) {
        MONTHLY -> "Monthly"
        YEARLY -> "Yearly"
        LIFETIME -> "Lifetime"
        else -> id
    }
    fun Time(id: String) = when (id) {
        MONTHLY -> "/Month"
        YEARLY -> "/Year"
        LIFETIME -> ""
        else -> id
    }
}
