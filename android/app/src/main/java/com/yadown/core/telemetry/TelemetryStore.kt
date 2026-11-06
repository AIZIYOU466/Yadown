package com.yadown.core.telemetry

import java.io.File

/**
 * 轻量事件日志：每行一个事件，超过上限轮转为 `.1`。
 * 不追求高吞吐；用于把运行时指标从进程内带到下一次会话。
 */
class TelemetryStore(
    private val file: File,
    private val maxBytes: Long = 5 * 1024 * 1024
) {

    @Synchronized
    fun append(line: String) {
        file.parentFile?.mkdirs()
        if (file.length() >= maxBytes) {
            val backup = File(file.parentFile, "${file.name}.1")
            backup.delete()
            file.renameTo(backup)
        }
        file.appendText(line + "\n")
    }

    fun readLines(): List<String> = if (file.exists()) file.readLines() else emptyList()
}
