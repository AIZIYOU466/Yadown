package com.yadown.feature.peer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yadown.core.peer.PairCandidate
import com.yadown.core.peer.PeerRow
import com.yadown.core.peer.PeerRuntime
import com.yadown.core.theme.Spacing
import com.yadown.core.theme.semanticColors

/**
 * 局域网加速入口：总开关、本机配对码、已发现设备与配对确认。
 * 配对短码来自对端实际 TLS 证书，mDNS 广播的指纹只用于命中信任库。
 */
@Composable
fun PeerListDialog(onDismiss: () -> Unit, viewModel: PeerListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var manualAddress by remember { mutableStateOf("") }

    pending?.let { candidate ->
        PairConfirmDialog(
            candidate = candidate,
            onConfirm = viewModel::confirmPair,
            onDismiss = viewModel::cancelPair
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("局域网加速") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("共享与发现", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "仅共享已完成内容，默认关闭",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText
                        )
                    }
                    Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                }

                state.shortCode?.let { shortCode ->
                    Text(
                        state.deviceName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "本机配对码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                    Text(
                        shortCode.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    "已共享 ${state.sharedCount} 项内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText
                )

                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (busy) {
                    Text(
                        "正在连接并读取对端证书…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }

                HorizontalDivider()

                when {
                    !state.enabled -> Text(
                        "开启后可与同一网络中的设备配对并互相加速",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                    state.peers.isEmpty() -> Text(
                        "正在发现同一网络中的设备…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                    else -> state.peers.forEach { row ->
                        PeerRowItem(
                            row = row,
                            busy = busy,
                            onPair = { viewModel.pairWith(row.peer) },
                            onUnpair = { viewModel.unpair(row.peer) }
                        )
                    }
                }

                HorizontalDivider()

                Text("手动配对", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualAddress,
                        onValueChange = { manualAddress = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = Spacing.sm),
                        placeholder = { Text("192.168.1.5:9000") },
                        singleLine = true
                    )
                    TextButton(
                        onClick = { viewModel.pairManual(manualAddress) },
                        enabled = manualAddress.isNotBlank() && !busy
                    ) {
                        Text("配对")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun PeerRowItem(row: PeerRow, busy: Boolean, onPair: () -> Unit, onUnpair: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.peer.name, style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append("${row.peer.host}:${row.peer.port}")
                    append(" · ")
                    append(if (row.trusted) "已配对" else "未配对")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText
            )
        }
        if (row.trusted) {
            TextButton(onClick = onUnpair) { Text("解除") }
        } else {
            TextButton(onClick = onPair, enabled = !busy) { Text("配对") }
        }
    }
}

@Composable
private fun PairConfirmDialog(
    candidate: PairCandidate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("与 ${candidate.name} 配对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "请确认对方设备上显示的本机配对码与下面一致：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    candidate.shortCode.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${candidate.host}:${candidate.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("一致，配对") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
