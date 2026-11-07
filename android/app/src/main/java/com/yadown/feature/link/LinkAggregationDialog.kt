package com.yadown.feature.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yadown.core.theme.Spacing
import com.yadown.core.theme.semanticColors

/** 多链路加速开关与当前链路展示。少于两条链路时下载自动回退单链路。 */
@Composable
fun LinkAggregationDialog(
    onDismiss: () -> Unit,
    viewModel: LinkAggregationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("多链路加速") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("同时使用多条网络", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Wi-Fi 与移动数据并行拉取分块",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText
                        )
                    }
                    Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("允许移动数据", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "会消耗流量，可能产生费用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText
                        )
                    }
                    Switch(
                        checked = state.allowMetered,
                        onCheckedChange = viewModel::setAllowMetered,
                        enabled = state.enabled
                    )
                }

                HorizontalDivider()

                Text("当前可用链路", style = MaterialTheme.typography.titleSmall)
                if (state.links.isEmpty()) {
                    Text(
                        "未发现可用链路",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                } else {
                    state.links.forEach { link ->
                        Text(
                            buildString {
                                append(link.label)
                                if (link.metered) append(" · 计费")
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Text(
                    "少于两条链路时自动回退单链路；局域网源只走 Wi-Fi/以太网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("分块多连接（A/B）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "关闭（默认）走 HTTP/2 单连接复用；开启强制 HTTP/1.1，" +
                                "每个并发分块各建一条 TCP。仅影响单链路，切换后下一个分块生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText
                        )
                    }
                    Switch(
                        checked = state.multiConnection,
                        onCheckedChange = viewModel::setMultiConnection
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
