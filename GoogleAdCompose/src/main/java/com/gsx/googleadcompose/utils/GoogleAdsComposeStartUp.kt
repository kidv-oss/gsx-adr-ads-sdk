package com.gsx.googleadcompose.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Auto-init [Prefs] khi app khởi động — KHÔNG cần gọi tay trong Application.
 *
 * ContentProvider được hệ thống tạo TRƯỚC Application.onCreate, nên [PreferencesManager] dùng được
 * ngay từ đầu. Đăng ký sẵn trong manifest của lib (authority = "${applicationId}.gad-prefs-init"),
 * app dùng lib tự có, không phải làm gì thêm.
 */
class GoogleAdsComposeStartUp : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.applicationContext?.let {
            Prefs.init(it)
            com.gsx.googleadcompose.GoogleAds.AdCore.appContext = it
            (it as? android.app.Application)?.let { app ->
                com.gsx.googleadcompose.GoogleAds.AdCore.registerActivityTracking(app)
            }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
