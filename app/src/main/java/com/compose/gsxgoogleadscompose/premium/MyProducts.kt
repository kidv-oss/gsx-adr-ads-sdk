package com.compose.gsxgoogleadscompose.premium

// Nguồn DUY NHẤT cho product ID — dùng cho cả BillingClient.init (App) lẫn UI (PremiumScreen).
// applicationId = "anime.girlfriend.app"
object MyProducts {
    const val WEEKLY = "anime.girlfriend.app.weekly"
    const val YEARLY = "anime.girlfriend.app.yearly1"
    const val LIFETIME = "lifetime"

    val subscriptions = listOf(YEARLY, WEEKLY)
    val nonConsumable = listOf(LIFETIME)

    val all = listOf(WEEKLY, YEARLY, LIFETIME)

    fun title(id: String) = when (id) {
        WEEKLY -> "Weekly"
        YEARLY -> "Yearly"
        LIFETIME -> "Lifetime"
        else -> id
    }
    fun Time(id: String) = when (id) {
        WEEKLY -> "/Week"
        YEARLY -> "/Year"
        LIFETIME -> ""
        else -> id
    }
}
