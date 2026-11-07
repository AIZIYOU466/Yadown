package com.yadown.feature.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yadown.core.net.LinkAggregationSettings
import com.yadown.core.net.LinkRegistry
import com.yadown.core.net.NetworkLink
import com.yadown.core.net.TransportTopology
import com.yadown.core.net.TransportTopologySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class LinkAggregationViewModel @Inject constructor(
    private val settings: LinkAggregationSettings,
    private val topologySettings: TransportTopologySettings,
    private val registry: LinkRegistry
) : ViewModel() {

    data class UiState(
        val enabled: Boolean,
        val allowMetered: Boolean,
        val multiConnection: Boolean,
        val links: List<NetworkLink>
    )

    private val _state = MutableStateFlow(
        UiState(
            enabled = settings.enabled,
            allowMetered = settings.allowMetered,
            multiConnection = topologySettings.topology == TransportTopology.MULTI_CONNECTION,
            links = emptyList()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshLinks()
    }

    fun setEnabled(enabled: Boolean) {
        settings.enabled = enabled
        _state.update { it.copy(enabled = enabled) }
        registry.invalidate()
        refreshLinks()
    }

    fun setAllowMetered(allow: Boolean) {
        settings.allowMetered = allow
        _state.update { it.copy(allowMetered = allow) }
    }

    /** 传输拓扑 A/B：开启后并发分块各建一条 TCP（HTTP/1.1），关闭走 HTTP/2 单连接复用。 */
    fun setMultiConnection(enabled: Boolean) {
        topologySettings.topology = if (enabled) {
            TransportTopology.MULTI_CONNECTION
        } else {
            TransportTopology.REUSE
        }
        _state.update { it.copy(multiConnection = enabled) }
    }

    fun refreshLinks() {
        viewModelScope.launch {
            val links = withContext(Dispatchers.IO) { registry.links() }
            _state.update { it.copy(links = links) }
        }
    }
}
