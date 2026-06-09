package com.gsx.googleadcompose.helper

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

object DialogHelper {

    /** Dialog loading: spinner xoay tròn + chữ. Non-cancelable. Trả dialog để [AlertDialog.dismiss]. */
    fun showLoading(activity: Activity, message: String = "Loading..."): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val gap = (16 * activity.resources.displayMetrics.density).toInt()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(activity))
            addView(TextView(activity).apply {
                text = message
                setPadding(gap, 0, 0, 0)
            })
        }
        return AlertDialog.Builder(activity)
            .setView(row)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    /**
     * Chặn 1 hành động sau khi kiểm tra mạng. Có mạng: chạy [onAvailable] ngay. Mất mạng: hiện
     * dialog no-network; bấm Retry sẽ check lại — có mạng thì dialog đóng và chạy [onAvailable],
     * không thì hiện lại. (Text dialog để tiếng Anh.)
     */
    fun requireNetwork(
        activity: Activity,
        title: String = "No internet connection",
        message: String = "An internet connection is required to continue. Please check your network.",
        onAvailable: () -> Unit,
    ) {
        if (NetworkHelper.isOnline(activity)) {
            onAvailable()
            return
        }
        showNoNetworkDialog(
            activity = activity,
            title = title,
            message = message,
            onRetry = { requireNetwork(activity, title, message, onAvailable) },
        )
    }

    /**
     * Hiện dialog "no internet" không hủy được. (Text trên dialog giữ tiếng Anh.)
     *
     * @param onRetry chạy khi user bấm Retry (đã dismiss sẵn).
     * @param onCancel khác null thì hiện nút Cancel; null thì chỉ có Retry (ép user kết nối lại —
     *   dialog cứ hiện lại tới khi có mạng).
     */
    fun showNoNetworkDialog(
        activity: Activity,
        title: String = "No internet connection",
        message: String = "An internet connection is required to continue. Please check your network.",
        onRetry: () -> Unit,
        onCancel: (() -> Unit)? = null,
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onCancel?.invoke()
            return
        }
        activity.runOnUiThread {
            val builder = AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Retry") { dialog, _ ->
                    dialog.dismiss()
                    onRetry()
                }
            if (onCancel != null) {
                builder.setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                    onCancel()
                }
            }
            builder.show()
        }
    }
}
