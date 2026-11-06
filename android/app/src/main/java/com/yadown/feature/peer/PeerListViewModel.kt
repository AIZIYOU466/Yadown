package com.yadown.feature.peer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yadown.core.peer.Peer
import com.yadown.core.peer.PeerDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@HiltViewModel
class PeerListViewModel @Inject constructor(
    private val discovery: PeerDiscovery
) : ViewModel() {

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        _error.value = null
        job = viewModelScope.launch {
            discovery.discover()
                .catch { _error.value = it.message ?: "mDNS 发现失败" }
                .collect { _peers.value = it }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
