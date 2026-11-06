package com.yadown.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yadown.core.theme.Radius
import com.yadown.core.theme.Spacing
import com.yadown.core.theme.semanticColors

/** 任务状态与 docs/ARCHITECTURE.md 的 Intent 状态机一一对应，UI 只是状态的投影。 */
enum class TaskState { PENDING, ACTIVE, PAUSED, COMPLETED, FAILED }

data class DownloadTaskUi(
    val id: String,
    val fileName: String,
    val state: TaskState,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val firstPlayable: Boolean = false
)

private val sampleTasks = listOf(
    DownloadTaskUi("1", "github-releases/app-debug.apk", TaskState.ACTIVE, 18_400_000, 42_000_000, 3_200_000, false),
    DownloadTaskUi("2", "lecture-01.mp4", TaskState.ACTIVE, 26_000_000, 380_000_000, 5_800_000, true),
    DownloadTaskUi("3", "manual.pdf", TaskState.PAUSED, 4_100_000, 12_000_000, 0, false),
    DownloadTaskUi("4", "archive.zip", TaskState.COMPLETED, 96_000_000, 96_000_000, 0, false)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Yadown", fontWeight = FontWeight.SemiBold) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* P4 接入新增任务 */ }) {
                Icon(Icons.Filled.Add, contentDescription = "新增下载")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            items(sampleTasks, key = { it.id }) { task ->
                TaskCard(task)
            }
        }
    }
}

@Composable
private fun TaskCard(task: DownloadTaskUi) {
    val semantic = MaterialTheme.semanticColors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.mdLarge),
        colors = CardDefaults.cardColors(containerColor = semantic.cardSurface)
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.sm))
                StateBadge(task.state)
            }
            Spacer(Modifier.height(Spacing.md))
            val fraction = if (task.totalBytes > 0) {
                (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${fmtBytes(task.downloadedBytes)} / ${fmtBytes(task.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.subtleText
                )
                Text(
                    text = if (task.state == TaskState.ACTIVE) "${fmtBytes(task.speedBytesPerSec)}/s" else stateLabel(task.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.subtleText
                )
            }
            if (task.firstPlayable) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "现在可播放",
                    style = MaterialTheme.typography.labelMedium,
                    color = semantic.success
                )
            }
        }
    }
}

@Composable
private fun StateBadge(state: TaskState) {
    val semantic = MaterialTheme.semanticColors
    val (label, color) = when (state) {
        TaskState.PENDING -> "等待" to semantic.subtleText
        TaskState.ACTIVE -> "下载中" to semantic.info
        TaskState.PAUSED -> "已暂停" to semantic.warning
        TaskState.COMPLETED -> "已完成" to semantic.success
        TaskState.FAILED -> "失败" to MaterialTheme.colorScheme.error
    }
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
}

private fun stateLabel(state: TaskState): String = when (state) {
    TaskState.PENDING -> "等待调度"
    TaskState.ACTIVE -> "下载中"
    TaskState.PAUSED -> "已暂停"
    TaskState.COMPLETED -> "已完成"
    TaskState.FAILED -> "失败"
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