package com.yadown.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yadown.MainActivity
import com.yadown.core.repo.DownloadRepository
import com.yadown.core.repo.TaskUi
import com.yadown.core.transfer.IntentState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var repository: DownloadRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(emptyList()))
        scope.launch {
            repository.observeTasks().collect { tasks ->
                notify(activeTasks(tasks))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun activeTasks(tasks: List<TaskUi>): List<TaskUi> =
        tasks.filter { it.state == IntentState.ACTIVE.name }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notify(active: List<TaskUi>) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(active))
    }

    private fun buildNotification(active: List<TaskUi>): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active.isEmpty()) "Yadown 准备下载" else "Yadown 正在下载")
            .setOngoing(true)
            .setContentIntent(openAppIntent())

        if (active.isNotEmpty()) {
            val downloaded = active.sumOf { it.downloadedBytes }
            val total = active.sumOf { it.totalBytes }
            builder.setContentText(
                "${active.size} 个任务 · ${fmtBytes(downloaded)} / ${fmtBytes(total)}"
            )
            if (total > 0) {
                val permille = (downloaded * 1000 / total).toInt().coerceIn(0, 1000)
                builder.setProgress(1000, permille, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        } else {
            builder.setContentText("等待任务")
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun fmtBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "%.1f %s".format(value, units[unit])
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1001
    }
}
