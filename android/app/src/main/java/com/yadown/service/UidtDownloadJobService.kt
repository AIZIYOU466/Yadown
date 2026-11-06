package com.yadown.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.yadown.MainActivity
import com.yadown.core.survival.DeferredSyncRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * UIDT 的 JobService 载体。真正的下载仍由 DownloadRepository 执行，
 * 这里只负责系统的用户主动传输生命周期与通知。
 */
class UidtDownloadJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val intentId = params.extras.getString(UidtScheduler.KEY_INTENT_ID) ?: return false
        val url = params.extras.getString(UidtScheduler.KEY_URL) ?: return false

        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            attachNotification(params)
        }

        scope.launch {
            try {
                // 失败由仓库落 FAILED 并由 UI 暴露；不在系统层无限重试。
                DeferredSyncRunner.resumeNow(intentId, url)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun attachNotification(params: JobParameters) {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Yadown 后台下载")
            .setContentText("用户主动下载进行中")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        setNotification(
            params,
            NOTIFICATION_ID,
            notification,
            JobService.JOB_END_NOTIFICATION_POLICY_DETACH
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台下载", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private companion object {
        const val CHANNEL_ID = "uidt_downloads"
        const val NOTIFICATION_ID = 1002
    }
}
