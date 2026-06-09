package com.gsx.googleadcompose.GoogleAds

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.lang.ref.WeakReference

/** Trạng thái chung cho ads: app context + cờ MobileAds + activity đang foreground. */
object AdCore {
    /** App context, set sẵn bởi ContentProvider lúc khởi động. */
    @Volatile
    var appContext: Context? = null

    /** True sau khi MobileAds.initialize hoàn tất (set trong AdmUMP). */
    @Volatile
    var isMobileAdsReady: Boolean = false

    /** Activity đang resumed (foreground). WeakReference -> không giữ leak. */
    private var activityRef: WeakReference<Activity>? = null

    /** Activity foreground còn sống, hoặc null. Dùng cho [com.gsx.googleadcompose.GoogleAds] show() không tham số. */
    val currentActivity: Activity?
        get() = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** Đăng ký theo dõi activity resumed. Gọi 1 lần lúc khởi động (ContentProvider). */
    fun registerActivityTracking(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                activityRef = WeakReference(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (activityRef?.get() === activity) activityRef = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }
}
