package com.yadown.feature.p0

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yadown.core.theme.Spacing
import com.yadown.p0.incremental.IncrementalInstallProbe
import com.yadown.p0.mdns.MdnsProbe
import com.yadown.p0.multilink.MultiLinkProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * P0 三项工程假设的真机探针入口，仅 debug 构建可见。
 * 结果需人工回填 p0/README.md 的记录表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P0ProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("https://speed.hetzner.de/100MB.bin") }
    var log by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("P0 探针") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text("T0.1 多链路聚合", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("测试 URL（需支持 Range）") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                log = "T0.1 运行中..."
                scope.launch(Dispatchers.IO) {
                    val results = runCatching { MultiLinkProbe.probe(context, url) }
                        .getOrElse {
                            log = "T0.1 失败: ${it.message}"
                            return@launch
                        }
                    val baseline = runCatching {
                        MultiLinkProbe.singleLinkBaseline(context, url)
                    }.getOrDefault(0.0)
                    log = buildString {
                        appendLine("T0.1 多链路结果")
                        results.forEach {
                            appendLine(
                                "${it.transport}: ${it.bytes} B / ${it.elapsedMs} ms / " +
                                    "%.1f B/s".format(it.bytesPerSec)
                            )
                        }
                        appendLine("单链路基线: %.1f B/s".format(baseline))
                    }
                }
            }) { Text("跑 T0.1") }

            Text("T0.2 mDNS", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = {
                    MdnsProbe.registerService(
                        context = context,
                        serviceName = "yadown-probe",
                        port = 8888,
                        onRegistered = { log = "T0.2 已注册服务" },
                        onFailed = { log = "T0.2 注册失败: $it" }
                    )
                }) { Text("注册") }
                Button(onClick = {
                    log = "T0.2 发现中..."
                    MdnsProbe.discoverServices(
                        context = context,
                        onResolved = { info, elapsedMs ->
                            log = "T0.2 发现 ${info.serviceName} @${info.host}:${info.port} / ${elapsedMs} ms"
                        },
                        onFailed = { log = "T0.2 发现失败: $it" }
                    )
                }) { Text("发现") }
            }

            Text("T0.3 增量安装", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                val cap = IncrementalInstallProbe.probe(context)
                log = "T0.3 sdk=${cap.sdkInt}, installer=${cap.installerAvailable}, " +
                    "stagedReadable=${cap.stagedReadable}, 降级=${cap.degradedPath}"
            }) { Text("探测") }

            Text("日志", style = MaterialTheme.typography.titleMedium)
            Text(log, style = MaterialTheme.typography.bodySmall)
        }
    }
}
