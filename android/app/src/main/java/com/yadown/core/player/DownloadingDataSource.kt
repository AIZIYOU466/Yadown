package com.yadown.core.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.yadown.core.transfer.RangeReadySignal
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 边下边播数据源：从本地文件读取；若请求区间尚未下载，则轮询等待下载完成后再返回。
 * 「是否已下载」由 [isDownloaded] 注入（通常查下载仓库的区间覆盖）。
 */
class DownloadingDataSource(
    private val filePath: String,
    private val intentId: String,
    private val isDownloaded: (String, Long, Long) -> Boolean
) : BaseDataSource(false) {

    private lateinit var file: File
    private var position = 0L
    private var bytesRemaining = 0L
    private var waitStartedAtMs = 0L

    override fun open(dataSpec: DataSpec): Long {
        file = File(filePath)
        position = dataSpec.position
        waitStartedAtMs = SystemClock.elapsedRealtime()
        val available = maxOf(0L, file.length() - position)
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            available
        }
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val end = position + toRead - 1
        // 未下载则等待；下载引擎在后台持续推进，就绪后立即返回。
        while (!isDownloaded(intentId, position, end)) {
            if (Thread.currentThread().isInterrupted) {
                throw IOException("播放已取消")
            }
            if (SystemClock.elapsedRealtime() - waitStartedAtMs > MAX_WAIT_MS) {
                throw IOException("等待下载超时，任务可能已暂停或失败")
            }
            // 下载完成一块会 signal，立即唤醒；超时只是兜底。
            RangeReadySignal.await(POLL_INTERVAL_MS)
        }
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(position)
            raf.readFully(buffer, offset, toRead)
        }
        position += toRead
        bytesRemaining -= toRead
        bytesTransferred(toRead)
        return toRead
    }

    override fun close() {}

    override fun getUri(): Uri = Uri.fromFile(file)

    private companion object {
        const val POLL_INTERVAL_MS = 150L
        const val MAX_WAIT_MS = 30_000L
    }
}

class DownloadingDataSourceFactory(
    private val filePath: String,
    private val intentId: String,
    private val isDownloaded: (String, Long, Long) -> Boolean
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        DownloadingDataSource(filePath, intentId, isDownloaded)
}