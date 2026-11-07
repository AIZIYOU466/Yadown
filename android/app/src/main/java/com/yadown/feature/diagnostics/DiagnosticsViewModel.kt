package com.yadown.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yadown.core.net.ConnectionMetrics
import com.yadown.core.source.HostTuningStore
import com.yadown.core.telemetry.SessionTelemetry
import com.yadown.core.telemetry.TransferTelemetry
import com.yadown.core.transfer.DynamicLimiter
import com.yadown.core.transfer.TransferLimiter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 运行诊断：只读聚合遥测/连接/配额，供真机对比 P95、stall、复用率时使用。 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val hostTuning: HostTuningStore
) : ViewModel() {

    data class UiState(
        val session: SessionTelemetry.Session? = null,
        val telemetry: TransferTelemetry.Snapshot? = null,
        val connections: ConnectionMetrics.Snapshot? = null,
        val limiter: DynamicLimiter.Snapshot? = null,
        val hostTuning: String = ""
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _state.value = UiState(
                    session = SessionTelemetry.latest(),
                    telemetry = TransferTelemetry.snapshot(),
                    connections = ConnectionMetrics.snapshot(),
                    limiter = TransferLimiter.snapshot(),
                    hostTuning = formatHostTuning()
                )
                delay(1_000)
            }
        }
    }

    /** 每个 host 列出所有已探索档位：只看最优档无法判断学习是否收敛，也看不出哪档被跳过了。 */
    private fun formatHostTuning(): String = hostTuning.snapshot().entries
        .take(2)
        .joinToString("\n") { (host, samples) ->
            val best = samples.values.maxByOrNull { it.throughput }
            val arms = samples.values.sortedBy { it.limit }.joinToString(" · ") { sample ->
                val mark = if (best != null && sample.limit == best.limit) "*" else ""
                "${sample.limit}→${formatMb(sample.throughput)}$mark(n=${sample.samples})"
            }
            "$host\n$arms"
        }

    private fun formatMb(bytesPerSec: Double): String =
        String.format(Locale.US, "%.2f", bytesPerSec / (1024.0 * 1024.0))
}
