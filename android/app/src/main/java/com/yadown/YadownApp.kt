package com.yadown

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.yadown.core.peer.PeerRuntime
import com.yadown.core.repo.DownloadRepository
import com.yadown.core.survival.DeferredSyncRunner
import com.yadown.core.telemetry.TelemetryStore
import com.yadown.core.telemetry.TransferTelemetry
import java.io.File
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class YadownApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var repository: DownloadRepository

    @Inject lateinit var peerRuntime: PeerRuntime

    override fun onCreate() {
        super.onCreate()
        // WorkManager 重建 Worker 时通过该入口继续未完成的可延迟补齐任务。
        DeferredSyncRunner.install { intentId, url ->
            repository.resumeDeferred(intentId, url)
        }
        DeferredSyncRunner.installDirect { intentId, url ->
            repository.executeDeferred(intentId, url)
        }
        TransferTelemetry.installStore(
            TelemetryStore(File(filesDir, "telemetry/events.ndjson"))
        )
        // 用户上次开启了局域网加速时，进程启动即恢复服务端与发现，无需再进页面。
        peerRuntime.restore()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
