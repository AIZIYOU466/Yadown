package com.yadown.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yadown.core.theme.Spacing
import com.yadown.core.theme.semanticColors
import java.util.Locale

/** debug 构建的诊断页：调度、连接复用与配额，用于真机性能对比。 */
@Composable
fun DiagnosticsDialog(
    onDismiss: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行诊断") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text("本次任务", style = MaterialTheme.typography.titleSmall)
                state.session?.let { session ->
                    Text(
                        "完成 ${session.chunksDone} · 失败 ${session.chunksFailed} · 重试 ${session.retries}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "字节 ${session.bytesDone} · stall ${session.stalls} · 慢裂解 ${session.slowSplits}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "耗时 ${formatDuration(session.elapsedMs)} · 平均 ${formatSpeed(session.avgBytesPerSec)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "峰值并发 ${session.peakConcurrentRequests} · 新建连接 ${session.connectStarts}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("传输拓扑 ${session.topology.ifEmpty { "未标注" }}" +
                        if (session.connectionBudget > 0) " · 并发预算 ${session.connectionBudget}" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (session.finishedAt == null) "进行中" else "已结束",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                } ?: Text(
                    "暂无会话数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText
                )

                HorizontalDivider()

                Text("进程累计（含历史）", style = MaterialTheme.typography.titleSmall)
                state.telemetry?.let { telemetry ->
                    Text(
                        "完成 ${telemetry.chunksDone} · 失败 ${telemetry.chunksFailed}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "stall ${telemetry.stalls}（${percent(telemetry.stallRate)}）· 慢裂解 ${telemetry.slowSplits}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "字节 ${telemetry.bytesDone}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                    if (telemetry.failureByCause.isNotEmpty()) {
                        Text(
                            "失败原因 " + telemetry.failureByCause.entries
                                .joinToString(" · ") { "${it.key}=${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                HorizontalDivider()

                Text("连接", style = MaterialTheme.typography.titleSmall)
                state.connections?.let { connections ->
                    Text(
                        "复用率 ${percent(connections.reuseRate)}" +
                            "（call ${connections.callStarts} / connect ${connections.connectStarts}）",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                HorizontalDivider()

                Text("配额", style = MaterialTheme.typography.titleSmall)
                state.limiter?.let { limiter ->
                    Text(
                        "全局 ${limiter.globalInUse}/${limiter.globalLimit}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (limiter.perKeyInUse.isNotEmpty()) {
                        Text(
                            "per-key " + limiter.perKeyInUse.entries
                                .joinToString(" · ") { "${it.key}=${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun percent(value: Double): String =
    String.format(Locale.US, "%.1f%%", value * 100.0)

private fun formatSpeed(bytesPerSec: Double): String =
    if (bytesPerSec >= 1024 * 1024) {
        String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024 * 1024))
    } else {
        String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024)
    }

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 60) "${seconds / 60}m${seconds % 60}s" else "${seconds}s"
}
