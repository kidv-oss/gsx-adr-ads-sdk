package com.gsx.googleadcompose.utils


class PreferencesManager {
    fun isLifetime(): Boolean {
        return Prefs[APP_IAP_LIFETIME, false]
    }

    fun isRemoveAds(): Boolean {
        return Prefs[APP_REMOVE_ADS, false]
    }

    fun isSUB(): Boolean {
        return Prefs[APP_SUB_PRO, false]
    }

    fun removeAds(isRemoveAds : Boolean) {
        Prefs[APP_REMOVE_ADS] = isRemoveAds
    }

    fun purchaseLifetime() {
        Prefs[APP_IAP_LIFETIME] = true
    }

    fun removeLifetime(){
        Prefs[APP_IAP_LIFETIME] = false
    }

    fun purchaseAndRestoreSuccess() {
        Prefs[APP_SUB_PRO] = true
    }

    fun purchaseFailed() {
        Prefs[APP_SUB_PRO] = false
    }

    /** Product ID đang sở hữu (sub/lifetime), "" nếu không. Dùng để hiện tên gói đã mua. */
    fun ownedProduct(): String = Prefs[APP_OWNED_PRODUCT, ""]

    fun setOwnedProduct(id: String?) {
        Prefs[APP_OWNED_PRODUCT] = id ?: ""
    }

    fun setShowOnBoard(isSave: Boolean) {
        Prefs[APP_SHOW_ONBOARD] = isSave
    }

    fun isShowOnBoard(): Boolean{
        return Prefs[APP_SHOW_ONBOARD, true]
    }

    fun resetCounterAds(keyCount: String) {
        Prefs[keyCount] = 0
    }

    fun getCounterAds(keyCount: String): Int {
        return Prefs[keyCount, 0]
    }

    fun saveCounterAds(keyCount: String, count: Int) {
        Prefs[keyCount] = count
    }

    fun saveCountRewardAds(id: Int, count: Int) {
        Prefs[APP_COUNT_REWARD_ADS + "$id"] = count
    }

    fun getCountRewardAds(id: Int): Int {
        return Prefs[APP_COUNT_REWARD_ADS + "$id", 0]
    }

    fun saveTotalRewardAds(id: Int, count: Int) {
        Prefs[APP_TOTAL_REWARD_ADS + "$id"] = count
    }

    fun getTotalRewardAds(id: Int): Int {
        return Prefs[APP_TOTAL_REWARD_ADS + "$id", 5]
    }

    companion object {
        private const val APP_IAP_LIFETIME        : String = "APP_IAP_LIFETIME"
        private const val APP_REMOVE_ADS          : String = "APP_REMOVE_ADS"
        private const val APP_SUB_PRO             : String = "APP_SUB_PRO"
        private const val APP_OWNED_PRODUCT       : String = "APP_OWNED_PRODUCT"
        private const val APP_SHOW_ONBOARD        : String = "APP_SHOW_ONBOARD"
        private const val APP_COUNT_REWARD_ADS    : String = "APP_COUNT_REWARD_ADS"
        private const val APP_TOTAL_REWARD_ADS    : String = "APP_TOTAL_REWARD_ADS"

        @Volatile private var instance: PreferencesManager? = null

        fun getInstance() =
            instance
                ?: synchronized(this) {
                    instance ?: PreferencesManager().also { instance = it }
                }
    }
}
