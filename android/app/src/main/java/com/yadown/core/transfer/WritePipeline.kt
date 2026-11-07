package com.yadown.core.transfer

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * 异步写盘管线：传输协程只提交写任务，不执行磁盘系统调用。
 *
 * 有界队列形成背压（默认 16 块），避免写入落后时把整文件堆进内存；
 * 单写线程用 `FileChannel.write(buffer, position)` 定位写，天然支持乱序块。
 * `flush()` 等待队列排空后对每个文件 `force(false)`，是「DB DONE 必须晚于数据持久化」的唯一落盘点。
 */
class WritePipeline(
    private val queueCapacity: Int = DEFAULT_CAPACITY
) : Closeable {

    class WriteTask(
        val filePath: String,
        val position: Long,
        val bytes: ByteArray
    )

    private val queue = ArrayBlockingQueue<WriteTask>(queueCapacity)
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private val channels = ConcurrentHashMap<String, FileChannel>()

    @Volatile
    private var pending = 0

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var running = true

    private val worker = thread(start = true, isDaemon = true, name = "yadown-write") {
        while (running) {
            val task = runCatching { queue.poll(100, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            try {
                writeFully(task)
            } catch (t: Throwable) {
                failure = t
            } finally {
                lock.withLock {
                    pending--
                    idle.signalAll()
                }
            }
        }
    }

    fun submit(task: WriteTask) {
        failure?.let { throw it }
        lock.withLock { pending++ }
        try {
            queue.put(task)
        } catch (t: Throwable) {
            lock.withLock {
                pending--
                idle.signalAll()
            }
            throw t
        }
    }

    /**
     * 等待已提交任务写完并落盘；有失败时抛出，由调用方归因存储错误。
     *
     * [syncMetadata] = false 时只 `force(false)`（只保证数据，不强制文件大小等元数据），
     * 用于周期提交；文件长度已在下载开始前预分配并 `sync`，最终完成时仍用
     * [syncMetadata] = true，因此「DB DONE 的块字节已持久化」不变式保持。
     */
    fun flush(syncMetadata: Boolean = true) {
        lock.withLock {
            while (pending > 0) {
                idle.await(100, TimeUnit.MILLISECONDS)
                failure?.let { throw it }
            }
        }
        failure?.let { throw it }
        channels.values.forEach { it.force(syncMetadata) }
        failure?.let { throw it }
    }

    override fun close() {
        try {
            runCatching { flush() }
        } finally {
            running = false
            channels.values.forEach { runCatching { it.close() } }
            channels.clear()
            worker.join(1_000)
        }
    }

    private fun writeFully(task: WriteTask) {
        val channel = channels.computeIfAbsent(task.filePath) { path ->
            FileChannel.open(
                File(path).toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE
            )
        }
        val buffer = ByteBuffer.wrap(task.bytes)
        var offset = task.position
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer, offset)
            if (written <= 0) throw IllegalStateException("写入未推进: ${task.filePath}@$offset")
            offset += written
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 16
    }
}
