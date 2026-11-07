package com.yadown.feature.peer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yadown.core.peer.PairCandidate
import com.yadown.core.peer.Peer
import com.yadown.core.peer.PeerRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PeerListViewModel @Inject constructor(
    private val runtime: PeerRuntime
) : ViewModel() {

    val state: StateFlow<PeerRuntime.State> = runtime.state

    private val _pending = MutableStateFlow<PairCandidate?>(null)
    val pending: StateFlow<PairCandidate?> = _pending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun setEnabled(enabled: Boolean) = runtime.setEnabled(enabled)

    fun pairWith(peer: Peer) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                _error.value = null
                val candidate = runtime.probe(peer.host, peer.port, peer.name)
                if (candidate == null) {
                    _error.value = "无法连接 ${peer.name}，请确认对端已开启局域网加速"
                } else {
                    _pending.value = candidate
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun pairManual(address: String) {
        if (_busy.value) return
        val parsed = PeerRuntime.parseAddress(address)
        if (parsed == null) {
            _error.value = "请输入 IP:端口，例如 192.168.1.5:9000"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                _error.value = null
                val candidate = runtime.probe(parsed.first, parsed.second, null)
                if (candidate == null) {
                    _error.value = "无法连接 ${parsed.first}:${parsed.second}"
                } else {
                    _pending.value = candidate
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun confirmPair() {
        _pending.value?.let { runtime.confirmPair(it) }
        _pending.value = null
    }

    fun cancelPair() {
        _pending.value = null
    }

    fun unpair(peer: Peer) {
        peer.fingerprint?.let { runtime.unpair(it) }
    }
}
