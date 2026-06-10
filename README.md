# GSX Google Ads Compose

[![](https://jitpack.io/v/kidv-oss/gsx-adr-ads-sdk.svg)](https://jitpack.io/#kidv-oss/gsx-adr-ads-sdk)

Thư viện gói **Google Mobile Ads (GMA Next-Gen SDK)**, **UMP consent** và **Google Play Billing v9** cho **Jetpack Compose**. Mọi loại ad bọc thành composable / helper: tự load, tự preload, tự destroy khi rời màn, tự chặn khi user premium hoặc offline.

- Module thư viện: `:GoogleAdCompose` (package `com.gsx.googleadcompose`)
- Module app demo: `:app`
- `minSdk = 24`

---

## Mục lục

1. [Cài đặt](#1-cài-đặt)
2. [Init UMP + Mobile Ads](#2-init-ump--mobile-ads) — **làm trước tiên**
3. [App Open ad](#3-app-open-ad)
4. [Native ad](#4-native-ad)
5. [Banner ad](#5-banner-ad)
6. [Interstitial ad](#6-interstitial-ad)
7. [Các ad khác (Reward, Reward Interstitial)](#7-các-ad-khác)
8. [Billing và các cái khác](#8-billing-và-các-cái-khác)

> **Thứ tự khởi tạo bắt buộc:** `init UMP` → ad sẵn sàng → dùng Open / Native / Banner / Inter / Reward. Mọi ad check `AdCore.isMobileAdsReady`; chưa init UMP thì báo lỗi `UMP_IS_NOT_ACTIVE`.

---

## 1. Cài đặt

### 1.1 Thêm dependency (JitPack)

`settings.gradle.kts` — thêm repo JitPack:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.kidv-oss:gsx-adr-ads-sdk:0.0.2")
}
```

Đổi version sang tag release mới nhất hoặc commit hash. Xem các tag ở [JitPack](https://jitpack.io/#kidv-oss/gsx-adr-ads-sdk).

Lib đã `api(...)` sẵn `ads.mobile.sdk` (GMA Next-Gen) + `billing.ktx`, app không cần khai báo lại.

### 1.2 AdMob App ID trong manifest

`AndroidManifest.xml` của app (UMP và Mobile Ads đều cần):

```xml
<application android:name=".App">
    <meta-data
        android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="YOUR_APPLICATION_ID" />
</application>
```

> `Prefs` tự init qua một `ContentProvider` của lib **trước** `Application.onCreate` — app **không** cần gọi tay.

### 1.3 Cấp ad unit id

Set ID trong `Application.onCreate` (đọc từ `keys.xml` hay backend tuỳ ý):

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        AdmConfigAdId.listBannerAdUnitID             = resources.getStringArray(R.array.banner_ad_units).toList()
        AdmConfigAdId.listInterstitialAdUnitID       = resources.getStringArray(R.array.interstitial_ad_units).toList()
        AdmConfigAdId.listRewardAdUnitID             = resources.getStringArray(R.array.rewarded_ad_units).toList()
        AdmConfigAdId.listRewardInterstitialAdUnitID = resources.getStringArray(R.array.rewarded_interstitial_ad_units).toList()
        AdmConfigAdId.listNativeAdUnitID             = resources.getStringArray(R.array.native_ad_units).toList()
        AdmConfigAdId.listOpenAdUnitID               = resources.getStringArray(R.array.app_open_ad_units).toList()
    }
}
```

Mỗi list nhiều ID → lib **xoay vòng** mỗi lần load. Mọi hàm load/show nhận `index`:
- `index = -1` → xoay vòng (mặc định)
- `index >= 0` → chọn cụ thể id thứ `index` (0-based)
- `customIds = listOf(...)` → xoay vòng list id truyền riêng thay cho list trong `AdmConfigAdId`

---

## 2. Init UMP + Mobile Ads

**Bước đầu tiên, làm ở splash.** Xin consent (UMP) trước, init Mobile Ads SDK chỉ khi consent cho phép.

```kotlin
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val ump = AdmUMP()   // tự lấy Activity an toàn từ context Compose

    LaunchedEffect(Unit) {
        ump.initUMP(
            // ads SẴN sàng -> preload native/open sớm (khỏi poll)
            onAdsInitialized = {
                AdmNativeSequenceState.init(listOf(0, 1, 2, 3, 4))
            },
            // xong việc xin consent -> rời splash, vào app
            gatherConsentFinished = { onDone() },
        )
    }
}
```

`initUMP(...)`:
- `testDeviceHashedIds: List<String>` — device test cho consent form.
- `showNetworkErrorDialog: Boolean = true` — offline thì hiện dialog Retry/Cancel thay vì fail.
- `onAdsInitialized` — bắn khi Mobile Ads init xong (ads sẵn sàng → lúc preload).
- `gatherConsentFinished` — bắn khi xong xin consent (form đóng / không cần / lỗi) → **lúc rời splash**. Init ad chạy nền song song, không block callback này.

Privacy options (xin lại consent trong Settings):

```kotlin
val ump = AdmUMP()
if (ump.isPrivacyOptionsRequired) {
    Button(onClick = { ump.showPrivacyOptionsForm() }) { Text("Privacy options") }
}
```

> Sau bước này `AdCore.isMobileAdsReady = true`; mọi ad bên dưới mới chạy được.

---

## 3. App Open ad

Dùng `AppOpenAdPreloader`: preload buffer, show bằng `pollAd`, không dialog loading. Hết hạn sau 4h tự reload. Hai cách dùng:

### 3.1 Resume — tự show khi app trở lại foreground

Gọi 1 lần trong `Application.onCreate`. Bỏ qua ON_START đầu (cold start để splash lo):

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // ... set ad id, init billing ...

        GlobalVariables.canShowOpenAd = true   // premium -> tự set false
        AdmOpenResume.start()                  // hoặc start { onPaid = { v -> track(v) } }
    }
}
```

Mỗi resume kiểm tra `GlobalVariables.canShowOpenAd` + `isShowPopup` (mutex full-screen).

### 3.2 Splash — cold start load + show ngay

Instance riêng cho splash, tự destroy khi rời màn:

```kotlin
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val openSplash = AdmOpen()   // helper -> AdmOpenAd, tự destroy
    val openIds = stringArrayResource(R.array.app_open_ad_units_custom).toList()

    // gọi khi giá/consent đã sẵn -> show open rồi vào app
    LaunchedEffect(ready) {
        if (ready) openSplash.showFromSplash(customIds = openIds) { onDone() }
    }
}
```

`showFromSplash(index, customIds, timeoutMs = 8000, onClosed)`: `onClosed` bắn **1 lần** khi ad đóng / lỗi / quá timeout → splash đi tiếp.

Hook: `onShowed` / `onDismissed` / `onClicked` / `onImpression` / `onPaid(AdValue)` / `onError(AdmErrorType)`.

---

## 4. Native ad

`NativeAdLoader.load` → render asset qua `NativeAdView` wrapper. Layout dựng sẵn: `NativeLayout.NORMAL`, `SMALL`, `SMALL_2`, `COLLAPSIBLE`.

### 4.1 Tự load (đơn giản nhất)

```kotlin
AdmNative(Modifier.fillMaxWidth(), layout = NativeLayout.NORMAL, index = -1) {
    backgroundColor = Color(0xFF222222)
    ctaColor = Color(0xFFFF6D00)
    onError = { type -> Log.w("ad", "$type") }
}
```

Chờ load → shimmer; lỗi/premium/offline → render rỗng.

### 4.2 Preload rồi show (app sở hữu ad)

```kotlin
// preload (vd Application/màn trước)
AdmNativeAd.preload { ad -> ad?.let { cache["home"] = it } }

// show
AdmNative(Modifier.fillMaxWidth(), nativeAd = cache["home"])   // null -> skip
```

> Ad preload do **app sở hữu** → tự gọi `ad.destroy()` khi xong.

### 4.3 Native tuần tự (onboarding nhiều trang)

Preload theo thứ tự, luôn giữ sẵn 1 ad cho lần show kế:

```kotlin
// init 1 lần (Splash/Application), list INDEX vào listNativeAdUnitID
AdmNativeSequenceState.init(listOf(0, 1, 2))

// mỗi trang cần native -> show id kế + preload tiếp
AdmNativeSequence(
    Modifier.fillMaxWidth(),
    active = page == pager.currentPage,   // trong Pager: chỉ consume trang đang hiện
) { ctaColor = Color(0xFF52DE11) }

// rời hẳn onboarding -> dọn buffer
AdmNativeSequenceState.release()
```

Màu layout config được: `backgroundColor`, `ctaColor`, `ctaTextColor`, `textColor`, `subTextColor`, `startMuted`.

---

## 5. Banner ad

`BannerAd.load` (suspend) → render trong `AndroidView`. Composable tự load / fill / destroy.

```kotlin
AdmBanner(Modifier.fillMaxWidth())                       // mặc định: ADAPTIVE anchored full-width

AdmBanner(Modifier.fillMaxWidth()) {                     // tuỳ biến
    size = AdmBannerSize.MEDIUM_RECTANGLE
    collapsible = AdmBannerCollapsible.BOTTOM
    onPaid = { v -> track(v) }
    onError = { type -> Log.w("ad", "$type") }
}
```

`size`: `ADAPTIVE` (mặc định, thay smart_banner) · `ADAPTIVE_LARGE` · `BANNER` (320x50) · `FULL_BANNER` · `LARGE_BANNER` · `LEADERBOARD` · `MEDIUM_RECTANGLE` (300x250).

`collapsible`: `NONE` / `TOP` / `BOTTOM` — chỉ chạy với size anchored adaptive (size khác tự ép về `ADAPTIVE`).

Đổi `size`/`collapsible` → tự reload. Đang load → shimmer. Premium/offline → render rỗng.

---

## 6. Interstitial ad

`InterstitialAdPreloader`: preload sẵn, show lấy bằng `pollAd`. `load` gọi sớm; `show` sẵn thì hiện dialog ngắn rồi show, chưa kịp thì dialog chờ buffer.

```kotlin
val inter = AdmInterstitial {        // helper -> AdmInterstitialAd, tự destroy khi rời màn
    onError = { type -> toast("$type") }
    onDismissed = { goNext() }        // ad đóng -> đi tiếp
}

LaunchedEffect(Unit) { inter.load() }    // bật preload sớm

Button(onClick = { inter.show() }) { Text("Show") }   // tự lấy activity foreground
```

Tuỳ chỉnh: `loadingText`, `loadTimeoutMs` (mặc định 12s), `showDelayMs` (mặc định 2s), `bufferSize`.

Hook: `onShowed` / `onDismissed` / `onComplete` / `onClicked` / `onImpression` / `onPaid` / `onAvailable` / `onExhausted` / `onError`.

> Mutex `GlobalVariables.isShowPopup` chặn 2 full-screen đè nhau. Double-tap show → bỏ qua.

---

## 7. Các ad khác

API y hệt Interstitial, thêm `onReward`.

### 7.1 Rewarded

```kotlin
val reward = AdmReward {
    onReward = { item -> grant(item.amount) }
    onError  = { type -> toast("$type") }
}
LaunchedEffect(Unit) { reward.load() }
Button(onClick = { reward.show() }) { Text("Watch & earn") }
```

### 7.2 Rewarded Interstitial

```kotlin
val ad = AdmRewardInterstitial {
    onReward = { item -> grant(item.amount) }
    onError  = { type -> toast("$type") }
}
ad.load()
ad.show()
```

Unit id lấy từ `listRewardAdUnitID` / `listRewardInterstitialAdUnitID`.

---

## 8. Billing và các cái khác

### 8.1 Init billing (Application)

```kotlin
object MyProducts {
    val subscriptions = listOf("app.weekly", "app.yearly")
    val nonConsumable = listOf("lifetime")
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        BillingClient.init(
            context = this,
            nonConsumableIds = MyProducts.nonConsumable,
            subscriptionIds  = MyProducts.subscriptions,
            // consumableIds = ...,            // nếu có
            // autoRefreshOnForeground = true  // mặc định: tự connect + restore mỗi foreground
        )
        BillingClient.startConnection()
    }
}
```

> Mỗi product ID chỉ được xuất hiện **1 lần** giữa các list (route đúng consume/acknowledge). `autoRefreshOnForeground = true` → app **không** cần tự gọi `startConnection`/`restorePurchases`.

### 8.2 Mua / sub / restore trong Compose

```kotlin
val billing = BillingAction()    // tự lấy Activity, không cần truyền tay

Button(onClick = { billing.subscribe(MyProducts.YEARLY) }) {
    Text("Yearly ${billing.getPrice(MyProducts.YEARLY) ?: "..."}")
}
Button(onClick = { billing.purchase(MyProducts.LIFETIME) }) { Text("Lifetime") }
Button(onClick = { billing.restore() }) { Text("Restore") }
```

`ComposeBilling`: `purchase` · `subscribe` · `changeSubscription` (upgrade/downgrade) · `restore` · `getOffers` · `getPrice` · `hasFreeTrial` · `getFreeTrialPeriod`.

### 8.3 Lắng nghe billing event

```kotlin
BillingEvents(
    onProductsFetched = { products -> /* render giá */ },
    onPurchased       = { ids -> toast("Đã mua $ids") },
    onRestoreFinished = { has -> toast(if (has) "Đã khôi phục" else "Không có gói") },
    onError           = { type, _ -> Log.w("billing", "$type") },
)
```

### 8.4 Trạng thái premium

```kotlin
val premium by rememberPremiumState()   // lifetime || sub || removeAds, reactive
if (premium) { /* ẩn ad, hiện badge */ }

// hoặc đọc thẳng:
PreferencesManager.getInstance().isSUB()        // có sub/lifetime
PreferencesManager.getInstance().isRemoveAds()  // remove-ads
```

Premium → lib tự set `GlobalVariables.isShowSub = false` + `canShowOpenAd = false`, mọi ad tự báo `CLIENT_HAVE_SUB` / `CLIENT_HAVE_BEEN_REMOVED_AD` và **không render**. Remove-ads do app tự cấp: `BillingClient.setRemoveAds(true)`.

---

## Tham khảo nhanh

### `AdmErrorType`

`LIST_AD_ID_IS_EMPTY` · `AD_ID_IS_NOT_EXIST` · `AD_IS_NOT_AVAILABLE` · `NETWORK_IS_NOT_AVAILABLE` · `UMP_IS_NOT_ACTIVE` · `ACTIVITY_IS_NOT_AVAILABLE` · `CLIENT_HAVE_SUB` · `CLIENT_HAVE_BEEN_REMOVED_AD` · `OTHER`

### `GlobalVariables`

| Cờ | Ý nghĩa |
|----|---------|
| `isShowSub` | Còn được hiện màn subscription không (premium → false) |
| `isShowPopup` | Mutex full-screen ad/dialog đang hiện |
| `canShowOpenAd` | Có được show app-open không (premium → false) |

### Log tags

`AdmUMP` · `GoogleAds/Open` · `GoogleAds/Native` · `GoogleAds/Banner` · `GoogleAds/Inter` · `GoogleAds/Reward` · `GoogleAds/RewardInter` · `GoogleAds/Billing`

Bật/tắt log mỗi controller qua `enableLogging` (`BillingClient.enableLogging` mặc định `false`).
