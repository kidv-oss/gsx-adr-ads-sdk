package com.compose.gsxgoogleadscompose

import android.app.Application
import com.compose.gsxgoogleadscompose.premium.MyProducts
import com.gsx.googleadcompose.GlobalVariables
import com.gsx.googleadcompose.GoogleAds.AdmOpenResume
import com.gsx.googleadcompose.GoogleIAP.BillingClient
import com.gsx.googleadcompose.data.AdmConfigAdId

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // App tự cấp ad unit id từ keys.xml -> lib đọc qua AdmConfigAdId.
        AdmConfigAdId.listBannerAdUnitID = resources.getStringArray(R.array.banner_ad_units).toList()
        AdmConfigAdId.listInterstitialAdUnitID = resources.getStringArray(R.array.interstitial_ad_units).toList()
        AdmConfigAdId.listRewardAdUnitID = resources.getStringArray(R.array.rewarded_ad_units).toList()
        AdmConfigAdId.listRewardInterstitialAdUnitID = resources.getStringArray(R.array.rewarded_interstitial_ad_units).toList()
        AdmConfigAdId.listNativeAdUnitID = resources.getStringArray(R.array.native_ad_units).toList()
        AdmConfigAdId.listOpenAdUnitID = resources.getStringArray(R.array.app_open_ad_units).toList()

        // applicationId = "anime.girlfriend.app"
        BillingClient.enableLogging = true
        BillingClient.init(
            context = this,
            nonConsumableIds = MyProducts.nonConsumable,
            subscriptionIds = MyProducts.subscriptions,
        )
        // Tự connect + fetch + restore mỗi foreground (autoRefreshOnForeground mặc định = true).
        BillingClient.startConnection()

        // App-open resume: tự show khi app trở lại foreground (bỏ qua cold start đầu).
        GlobalVariables.canShowOpenAd = true
        AdmOpenResume.start()
    }
}
