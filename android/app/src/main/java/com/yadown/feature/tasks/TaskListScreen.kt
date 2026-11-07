package com.yadown.feature.tasks

import android.content.Intent
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yadown.BuildConfig
import com.yadown.core.repo.TaskUi
import com.yadown.core.semantic.AvailabilityContract
import com.yadown.core.survival.BatteryGuidance
import com.yadown.core.survival.PlatformProbe
import com.yadown.core.theme.Radius
import com.yadown.core.theme.Spacing
import com.yadown.core.theme.semanticColors
import com.yadown.core.transfer.IntentState
import com.yadown.feature.p0.P0ProbeScreen
import com.yadown.feature.peer.PeerListDialog
import com.yadown.feature.link.LinkAggregationDialog
import com.yadown.feature.diagnostics.DiagnosticsDialog
import com.yadown.feature.player.PlayerActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(viewModel: TaskListViewModel = hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var showP0 by remember { mutableStateOf(false) }
    var showPeers by remember { mutableStateOf(false) }
    var showLinks by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showDebugMenu by remember { mutableStateOf(false) }
    var showBackgroundGuide by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TaskUi?>(null) }

    if (showP0) {
        P0ProbeScreen(onBack = { showP0 = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Yadown",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    TextButton(onClick = { showBackgroundGuide = true }) { Text("后台") }
                    TextButton(onClick = { showPeers = true }) { Text("附近") }
                    TextButton(onClick = { showLinks = true }) { Text("多链路") }
                    if (BuildConfig.DEBUG) {
                        Box {
                            IconButton(onClick = { showDebugMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多调试入口")
                            }
                            DropdownMenu(
                                expanded = showDebugMenu,
                                onDismissRequest = { showDebugMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("P0 验证") },
                                    onClick = {
                                        showDebugMenu = false
                                        showP0 = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("运行诊断") },
                                    onClick = {
                                        showDebugMenu = false
                                        showDiagnostics = true
                                    }
                                )
                            }
                        }
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
                    TaskCard(
                        task = task,
                        viewModel = viewModel,
                        onDelete = { pendingDelete = it }
                    )
                }
            }
        }
    }

    if (showBackgroundGuide) {
        BackgroundGuideDialog(onDismiss = { showBackgroundGuide = false })
    }

    if (showPeers) {
        PeerListDialog(onDismiss = { showPeers = false })
    }

    if (showLinks) {
        LinkAggregationDialog(onDismiss = { showLinks = false })
    }

    if (showDiagnostics) {
        DiagnosticsDialog(onDismiss = { showDiagnostics = false })
    }

    if (showAddDialog) {
        AddTaskDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { urls ->
                viewModel.createTask(urls)
                showAddDialog = false
            }
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("无法创建任务") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) { Text("知道了") }
            }
        )
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除任务") },
            text = { Text("将删除任务记录和已下载的文件，确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTask(task)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TaskCard(
    task: TaskUi,
    viewModel: TaskListViewModel,
    onDelete: (TaskUi) -> Unit
) {
    val context = LocalContext.current
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
            if (task.availableNow && task.state != IntentState.COMPLETED.name) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = availableLabel(task),
                    style = MaterialTheme.typography.labelMedium,
                    color = semantic.success
                )
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
                    text = buildString {
                        append("${fmtBytes(task.downloadedBytes)} / ${fmtBytes(task.totalBytes)}")
                        if (task.sourceCount > 1) append(" · ${task.sourceCount} 源")
                        if (task.state == IntentState.ACTIVE.name && task.speedBytesPerSec > 0) {
                            append(" · ${fmtBytes(task.speedBytesPerSec)}/s")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.subtleText,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    val capability = task.capability
                    if (task.state == IntentState.COMPLETED.name) {
                        val isApk = task.fileName.endsWith(".apk", ignoreCase = true)
                        val isVideo = task.fileName.endsWith(".mp4", ignoreCase = true)
                        TextButton(onClick = {
                            runCatching {
                                if (isVideo) {
                                    context.startActivity(
                                        Intent(context, PlayerActivity::class.java).apply {
                                            putExtra(PlayerActivity.EXTRA_PATH, viewModel.filePath(task))
                                            putExtra(PlayerActivity.EXTRA_TITLE, task.fileName)
                                            putExtra(PlayerActivity.EXTRA_INTENT_ID, task.id)
                                        }
                                    )
                                } else {
                                    context.startActivity(viewModel.openIntent(task))
                                }
                            }
                        }) {
                            Text(if (isApk) "安装" else "打开")
                        }
                    } else if (task.availableNow) {
                        // 关键区域已就绪：不必等全文下完就能进入播放/预览/安装。
                        // 只有视频具备真正的边下边播；其余格式等整包完成后再提供入口。
                        val action = when (capability) {
                            AvailabilityContract.Capability.PLAYABLE.name -> "播放"
                            else -> null
                        }
                        if (action != null) {
                            TextButton(onClick = {
                                runCatching {
                                    if (capability == AvailabilityContract.Capability.PLAYABLE.name) {
                                        context.startActivity(
                                            Intent(context, PlayerActivity::class.java).apply {
                                                putExtra(PlayerActivity.EXTRA_PATH, viewModel.filePath(task))
                                                putExtra(PlayerActivity.EXTRA_TITLE, task.fileName)
                                                putExtra(PlayerActivity.EXTRA_INTENT_ID, task.id)
                                            }
                                        )
                                    } else {
                                        context.startActivity(viewModel.openIntent(task))
                                    }
                                }
                            }) { Text(action) }
                        }
                    }
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
                    }
                    TextButton(onClick = { onDelete(task) }) { Text("删除") }
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
private fun AddTaskDialog(onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    var urls by remember { mutableStateOf("") }
    val parsed = remember(urls) {
        urls.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增下载") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = urls,
                    onValueChange = { urls = it },
                    label = { Text("下载链接") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "第一行为主源，其余行作为镜像；只有与主源大小、ETag/Last-Modified 一致的镜像才会启用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(parsed) },
                enabled = parsed.isNotEmpty()
            ) { Text("开始") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun availableLabel(task: TaskUi): String = when (task.capability) {
    AvailabilityContract.Capability.PLAYABLE.name -> "关键区域已就绪·现在可播放"
    else -> "关键区域已就绪"
}

@Composable
private fun BackgroundGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val guidance = remember { BatteryGuidance.guidance(PlatformProbe.detectRom()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(guidance.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                guidance.steps.forEach { step ->
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "否则系统可能在应用退到后台后杀掉下载进程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { context.startActivity(BatteryGuidance.settingsIntent(context, guidance)) }
                onDismiss()
            }) { Text("去设置") }
        },
        dismissButton = {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(BatteryGuidance.batteryOptimizationIntent(context))
                }
                onDismiss()
            }) { Text("电池白名单") }
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
