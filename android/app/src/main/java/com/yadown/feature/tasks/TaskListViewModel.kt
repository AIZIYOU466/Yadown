package com.yadown.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yadown.core.repo.DownloadRepository
import com.yadown.core.repo.TaskUi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val repository: DownloadRepository
) : ViewModel() {

    val tasks: StateFlow<List<TaskUi>> = repository.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createTask(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch { repository.createAndStart(url) }
    }

    fun pause(task: TaskUi) {
        repository.pause(task.id)
    }

    fun resume(task: TaskUi) {
        repository.resume(task.id, task.sourceUrl)
    }
}
