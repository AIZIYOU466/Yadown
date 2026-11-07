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

    private fun formatHostTuning(): String = hostTuning.snapshot().entries
        .take(2)
        .joinToString("\n") { (host, samples) ->
            val best = samples.values.maxByOrNull { it.throughput }
            if (best == null) host else {
                "$host → ${best.limit} 连接（${(best.throughput / 1_000_000).formatMb() }MB/s, n=${best.samples}）"
            }
        }

    private fun Double.formatMb(): String = String.format("%.2f", this)
}
