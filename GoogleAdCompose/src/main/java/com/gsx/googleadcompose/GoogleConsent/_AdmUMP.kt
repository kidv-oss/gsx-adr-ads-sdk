package com.gsx.googleadcompose.GoogleConsent

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import com.gsx.googleadcompose.helper.DialogHelper
import com.gsx.googleadcompose.helper.NetworkHelper
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.MobileAds.Companion.initialize
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Điều phối UMP (User Messaging Platform) consent + init Mobile Ads cho GMA Next-Gen SDK.
 *
 * Thứ tự bắt buộc: xin consent trước, chỉ init ad khi consent cho phép. AdMob App ID đọc từ
 * manifest meta-data `com.google.android.gms.ads.APPLICATION_ID` (UMP cũng cần cái này).
 *
 * Trong Compose, gọi composable [AdmUMP] để lấy instance (tự lấy Activity an toàn):
 * ```
 * val ump = AdmUMP()
 * LaunchedEffect(Unit) {
 *     ump.initUMP(gatherConsentFinished = { /* ready -> vào app */ })
 * }
 * ```
 *
 * @param activity Activity dùng để hiện consent form.
 */
class _AdmUMP(private val activity: Activity) {

    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(activity)

    private val isAdsInitCalled = AtomicBoolean(false)

    /** True khi trạng thái consent cho phép request ad. */
    val canRequestAds: Boolean
        get() = consentInformation.canRequestAds()

    /** True nếu cần cung cấp lối vào privacy options (xin lại consent) trong app. */
    val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Xin consent, sau đó init Mobile Ads SDK nếu được phép.
     *
     * [gatherConsentFinished] gọi khi xong việc xin consent (form đóng, không cần, hoặc update lỗi)
     * — đây là lúc rời splash. Init ad chạy nền song song, không block callback này.
     *
     * Nếu offline thì UMP update sẽ fail INTERNET_ERROR. Khi [showNetworkErrorDialog] = true sẽ hiện
     * dialog retry thay vì fail; ngược lại chạy tiếp với consent đã cache.
     *
     * @param testDeviceHashedIds các device hashed ID coi là test device cho consent form.
     * @param showNetworkErrorDialog hiện dialog Retry/Cancel khi không có mạng.
     * @param gatherConsentFinished gọi khi xong việc xin consent.
     */
    fun initUMP(
        testDeviceHashedIds: List<String> = emptyList(),
        showNetworkErrorDialog: Boolean = true,
        onAdsInitialized: () -> Unit = {},
        gatherConsentFinished: () -> Unit,
    ) {
        // Offline -> UMP không fetch form được. Hiện dialog retry (hoặc chạy tiếp nếu tắt dialog).
        if (!NetworkHelper.isOnline(activity)) {
            if (showNetworkErrorDialog) {
                DialogHelper.showNoNetworkDialog(
                    activity = activity,
                    onRetry = { initUMP(testDeviceHashedIds, showNetworkErrorDialog, onAdsInitialized, gatherConsentFinished) },
                    onCancel = {
                        if (canRequestAds) initializeAds(testDeviceHashedIds, onAdsInitialized)
                        gatherConsentFinished()
                    },
                )
            } else {
                if (canRequestAds) initializeAds(testDeviceHashedIds, onAdsInitialized)
                gatherConsentFinished()
            }
            return
        }

        val paramsBuilder = ConsentRequestParameters.Builder()
        if (testDeviceHashedIds.isNotEmpty()) {
            val debug = ConsentDebugSettings.Builder(activity)
            testDeviceHashedIds.forEach { debug.addTestDeviceHashedId(it) }
            paramsBuilder.setConsentDebugSettings(debug.build())
        }

        // Thử init ad bằng consent của phiên trước, trước khi form xử lý xong.
        if (canRequestAds) initializeAds(testDeviceHashedIds, onAdsInitialized)

        consentInformation.requestConsentInfoUpdate(
            activity,
            paramsBuilder.build(),
            {
                // Update thành công — hiện form nếu cần, rồi chạy tiếp.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError: FormError? ->
                    if (formError != null) {
                        Log.w(TAG, "Consent form error ${formError.errorCode}: ${formError.message}")
                    }
                    if (canRequestAds) initializeAds(testDeviceHashedIds, onAdsInitialized)
                    gatherConsentFinished()
                }
            },
            { requestError: FormError ->
                // Update lỗi — chạy tiếp với consent hiện đang có.
                Log.w(TAG, "Consent update failed ${requestError.errorCode}: ${requestError.message}")
                if (canRequestAds) initializeAds(testDeviceHashedIds, onAdsInitialized)
                gatherConsentFinished()
            },
        )
    }

    /**
     * Hiện privacy options form (gọi từ lối vào trong settings khi [isPrivacyOptionsRequired] = true).
     */
    fun showPrivacyOptionsForm(onDismissed: (FormError?) -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error -> onDismissed(error) }
    }

    /** Reset consent đã lưu. Chỉ dùng debug. */
    fun resetConsent() = consentInformation.reset()

    private fun initializeAds(testDeviceHashedIds: List<String>, onAdsInitialized: () -> Unit = {}) {
        if (isAdsInitCalled.getAndSet(true)) return

        val appId = readAppId()
        if (appId == null) {
            Log.e(TAG, "Missing manifest meta-data com.google.android.gms.ads.APPLICATION_ID")
            isAdsInitCalled.set(false)
            return
        }

        // GMA Next-Gen SDK phải init ngoài main thread để tránh ANR.
        CoroutineScope(Dispatchers.IO).launch {
            initialize(
                activity.applicationContext,
                InitializationConfig.Builder(appId).build(),
            ) {
                Log.d(TAG, "Mobile Ads SDK initialized.")
                com.gsx.googleadcompose.GoogleAds.AdCore.isMobileAdsReady = true
                activity.runOnUiThread { onAdsInitialized() }   // ads sẵn -> báo app (preload...)
            }
            if (testDeviceHashedIds.isNotEmpty()) {
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder()
                        .setTestDeviceIds(testDeviceHashedIds)
                        .build()
                )
            }
        }
    }

    private fun readAppId(): String? = runCatching {
        val pm = activity.packageManager
        val info = pm.getApplicationInfo(activity.packageName, PackageManager.GET_META_DATA)
        info.metaData?.getString("com.google.android.gms.ads.APPLICATION_ID")
    }.getOrNull()

    private companion object {
        const val TAG = "AdmUMP"
    }
}
