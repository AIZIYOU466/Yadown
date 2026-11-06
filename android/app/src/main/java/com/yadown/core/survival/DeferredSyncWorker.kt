package com.yadown.core.survival

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * P2/T2.2：可延迟后台补齐的 Carrier 实现。
 *
 * 依据 docs/ARCHITECTURE.md §5.4 的 Carrier 决策表：
 * 「用户主动下载」由前台服务承载；「可延迟的后台补齐」交给 WorkManager ——
 * 系统在 Doze/充电等约束满足时才调度，避免应用自己起服务被 ROM 掐断后反复重启。
 *
 * 约束选择：下载必须有网，且只在非计费网络下做补齐，避免用户流量被后台任务消耗。
 */
@HiltWorker
class DeferredSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val intentId = inputData.getString(KEY_INTENT_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        return DeferredSyncRunner.resume(intentId, url)
    }

    companion object {
        const val KEY_INTENT_ID = "deferred.intentId"
        const val KEY_URL = "deferred.url"

        private const val UNIQUE_PREFIX = "yadown-deferred-"

        /** 入队一个可延迟补齐任务；同名任务存在时保留已有调度。 */
        fun enqueue(context: Context, intentId: String, url: String) {
            val request = OneTimeWorkRequestBuilder<DeferredSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(
                    androidx.work.workDataOf(
                        KEY_INTENT_ID to intentId,
                        KEY_URL to url
                    )
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_PREFIX + intentId, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, intentId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PREFIX + intentId)
        }
    }
}

/**
 * Worker 与下载仓库之间的转接点。
 *
 * WorkManager 在应用进程重建时会重新实例化 Worker，此时 Hilt 可以正常注入仓库，
 * 但 Worker 本身不应直接依赖下载引擎的具体实现细节（避免 core.survival 反向依赖 core.repo）。
 * 因此由应用启动时注册一个 [Runner]，把「继续下载」这一动作注入进来。
 */
object DeferredSyncRunner {
    /** 返回 true 表示片段已推进（Result.success），false 表示应重试（Result.retry）。 */
    fun interface Runner {
        suspend fun resume(intentId: String, url: String): Boolean
    }

    @Volatile
    private var runner: Runner? = null

    fun install(runner: Runner) {
        this.runner = runner
    }

    suspend fun resume(intentId: String, url: String): ListenableWorker.Result {
        val active = runner ?: return ListenableWorker.Result.failure()
        return if (active.resume(intentId, url)) {
            ListenableWorker.Result.success()
        } else {
            ListenableWorker.Result.retry()
        }
    }
}
