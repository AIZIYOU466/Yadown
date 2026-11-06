package com.yadown.feature.tasks

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yadown.core.repo.DownloadRepository
import com.yadown.core.repo.TaskUi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val repository: DownloadRepository
) : ViewModel() {

    val tasks: StateFlow<List<TaskUi>> = repository.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun createTask(urls: List<String>) {
        val cleaned = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val primary = cleaned.firstOrNull() ?: return
        viewModelScope.launch {
            // 探测失败（无效 URL、服务器不返回长度）不能让异常逃出协程导致崩溃。
            runCatching { repository.createAndStart(primary, cleaned.drop(1)) }
                .onFailure { _error.value = it.message ?: "创建任务失败" }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun pause(task: TaskUi) {
        repository.pause(task.id)
    }

    fun resume(task: TaskUi) {
        repository.resume(task.id, task.sourceUrl)
    }

    fun openIntent(task: TaskUi): Intent = repository.openIntent(task)

    fun filePath(task: TaskUi): String = repository.filePath(task)

    fun deleteTask(task: TaskUi) = repository.deleteTask(task)
}
