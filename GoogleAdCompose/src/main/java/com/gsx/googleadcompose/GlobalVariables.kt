package com.gsx.googleadcompose

/** Cờ ad/UI runtime toàn app (reset mỗi lần mở app). Trạng thái cần lưu thì để trong prefs. */
object GlobalVariables {
    /** Còn được hiện màn subscription không. Đặt false khi user đã premium. */
    var isShowSub: Boolean = true

    /** Đang hiện 1 full-screen ad / dialog loading (mutex chặn full-screen đè lên nhau). */
    var isShowPopup: Boolean = false

    /** Có được hiện app-open ad không. Đặt false khi user đã premium. */
    var canShowOpenAd: Boolean = false

    /** App đã dùng quá 4 giờ chưa (dùng để chặn app-open theo hạn). */
    var hasUsing4Hours: Boolean = true
}
