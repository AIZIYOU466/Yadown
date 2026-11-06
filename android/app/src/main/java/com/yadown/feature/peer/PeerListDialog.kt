package com.yadown.feature.peer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yadown.core.peer.Peer
import com.yadown.core.theme.Spacing
import com.yadown.core.theme.semanticColors

/**
 * 仅 debug 构建可见的局域网发现预览：只展示 peer_index 的发现结果，
 * 不提供传输入口（T4.3 依赖 P0.2 与信任模型评审）。
 */
@Composable
fun PeerListDialog(onDismiss: () -> Unit, viewModel: PeerListViewModel = hiltViewModel()) {
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }
    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("附近设备") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                when {
                    error != null -> Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    peers.isEmpty() -> Text(
                        "正在发现同一网络中的 Yadown 设备…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                    else -> peers.forEach { peer -> PeerRow(peer) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun PeerRow(peer: Peer) {
    Text(
        text = peer.name,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = buildString {
            append("${peer.host}:${peer.port}")
            append(" · ")
            append(if (peer.contentHashes.isEmpty()) "未声明内容" else "${peer.contentHashes.size} 项内容")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.semanticColors.subtleText
    )
}
