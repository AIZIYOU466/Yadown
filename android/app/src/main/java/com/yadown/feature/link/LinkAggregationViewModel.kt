package com.yadown.feature.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yadown.core.net.LinkAggregationSettings
import com.yadown.core.net.LinkRegistry
import com.yadown.core.net.NetworkLink
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
    private val registry: LinkRegistry
) : ViewModel() {

    data class UiState(
        val enabled: Boolean,
        val allowMetered: Boolean,
        val links: List<NetworkLink>
    )

    private val _state = MutableStateFlow(
        UiState(
            enabled = settings.enabled,
            allowMetered = settings.allowMetered,
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

    fun refreshLinks() {
        viewModelScope.launch {
            val links = withContext(Dispatchers.IO) { registry.links() }
            _state.update { it.copy(links = links) }
        }
    }
}
