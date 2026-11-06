package com.yadown.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle

/**
 * UIDT 调度器：用户主动发起的长下载交给 JobScheduler，
 * 避免 dataSync 前台服务在 Android 15+ 的 6 小时累计上限。
 */
object UidtScheduler {

    const val KEY_INTENT_ID = "uidt.intentId"
    const val KEY_URL = "uidt.url"

    fun schedule(context: Context, intentId: String, url: String, totalBytes: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false

        val extras = PersistableBundle().apply {
            putString(KEY_INTENT_ID, intentId)
            putString(KEY_URL, url)
        }
        val network = NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val builder = JobInfo.Builder(
            intentId.hashCode(),
            ComponentName(context, UidtDownloadJobService::class.java)
        )
            .setRequiredNetwork(network)
            .setUserInitiated(true)
            .setExtras(extras)

        if (totalBytes > 0) {
            builder.setEstimatedNetworkBytes(totalBytes, totalBytes)
        }

        return runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
    }

    fun cancel(context: Context, intentId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            context.getSystemService(JobScheduler::class.java)?.cancel(intentId.hashCode())
        }
    }
}
