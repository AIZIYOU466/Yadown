package com.yadown.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yadown.BuildConfig
import com.yadown.core.repo.TaskUi
import com.yadown.core.theme.Radius
import com.yadown.core.theme.Spacing
import com.yadown.core.theme.semanticColors
import com.yadown.core.transfer.IntentState
import com.yadown.feature.p0.P0ProbeScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(viewModel: TaskListViewModel = hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    var showP0 by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    if (showP0) {
        P0ProbeScreen(onBack = { showP0 = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Yadown", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (BuildConfig.DEBUG) {
                        TextButton(onClick = { showP0 = true }) { Text("P0") }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新增下载")
            }
        }
    ) { padding ->
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "还没有下载任务，点右下角 + 添加",
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(task = task, viewModel = viewModel)
                }
            }
        }
    }

    if (showAddDialog) {
        AddTaskDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { url ->
                viewModel.createTask(url)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun TaskCard(task: TaskUi, viewModel: TaskListViewModel) {
    val semantic = MaterialTheme.semanticColors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.mdLarge),
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${fmtBytes(task.downloadedBytes)} / ${fmtBytes(task.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.subtleText
                )
                when (task.state) {
                    IntentState.ACTIVE.name -> TextButton(onClick = { viewModel.pause(task) }) {
                        Text("暂停")
                    }
                    IntentState.PAUSED.name -> TextButton(onClick = { viewModel.resume(task) }) {
                        Text("继续")
                    }
                    IntentState.FAILED.name -> TextButton(onClick = { viewModel.resume(task) }) {
                        Text("重试")
                    }
                    else -> Text(
                        text = if (task.state == IntentState.COMPLETED.name) "已完成" else "等待",
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.subtleText
                    )
                }
            }
        }
    }
}

@Composable
private fun StateBadge(state: String) {
    val semantic = MaterialTheme.semanticColors
    val (label, color) = when (state) {
        IntentState.ACTIVE.name -> "下载中" to semantic.info
        IntentState.PAUSED.name -> "已暂停" to semantic.warning
        IntentState.COMPLETED.name -> "已完成" to semantic.success
        IntentState.FAILED.name -> "失败" to MaterialTheme.colorScheme.error
        else -> "等待" to semantic.subtleText
    }
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增下载") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("下载链接") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank()
            ) { Text("开始") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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
