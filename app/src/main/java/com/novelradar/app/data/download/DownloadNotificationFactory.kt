package com.novelradar.app.data.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import com.novelradar.app.MainActivity

class DownloadNotificationFactory(private val context: Context) {
    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "整本下载", NotificationManager.IMPORTANCE_LOW))
    }

    /** Task7 requests this permission from a user action; denial does not gate foreground work. */
    fun permissionRequestRequired(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun foreground(taskId: String, completed: Int, total: Int): ForegroundInfo {
        createChannel()
        val id = (taskId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("整本下载")
            .setContentText(if (total > 0) "已完成 $completed / $total 章" else "正在读取已保存目录")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS).setProgress(total, completed, total <= 0).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, notification)
    }

    companion object { const val CHANNEL = "book_downloads" }
}
