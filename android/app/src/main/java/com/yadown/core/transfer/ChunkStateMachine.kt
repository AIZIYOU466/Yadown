package com.yadown.core.transfer

/** Chunk 状态机，转换规则见 docs/ARCHITECTURE.md §3.1。 */
enum class ChunkState { PENDING, RUNNING, DONE, FAILED, INVALIDATED }

sealed interface ChunkEvent {
    /** 调度器分配连接 */
    data object Schedule : ChunkEvent

    /** Range 校验通过 */
    data object Validated : ChunkEvent

    /** 无进展超时 / 网络错误 / 校验失败 */
    data class Failed(val cause: FailureCause) : ChunkEvent

    /** 重试预算内重排 */
    data object Retry : ChunkEvent

    /** 交叉校验发现文件空洞 */
    data class HoleDetected(val cause: FailureCause = FailureCause.CHECKSUM_MISMATCH) : ChunkEvent

    /** 换源后重新调度 */
    data object SwitchSource : ChunkEvent

    /** 裂解/重置 */
    data object Reset : ChunkEvent
}

object ChunkStateMachine {
    const val MAX_RETRIES = 3

    /**
     * 纯函数转换：给定当前状态、事件与已重试次数，返回新状态。
     * 未定义的事件组合保持原状态，避免调用方需要防御性判断。
     */
    fun transition(current: ChunkState, event: ChunkEvent, retryCount: Int): ChunkState =
        when (current) {
            ChunkState.PENDING -> when (event) {
                ChunkEvent.Schedule -> ChunkState.RUNNING
                ChunkEvent.Reset -> ChunkState.PENDING
                else -> current
            }

            ChunkState.RUNNING -> when (event) {
                is ChunkEvent.Failed -> ChunkState.FAILED
                ChunkEvent.Validated -> ChunkState.DONE
                else -> current
            }

            ChunkState.FAILED -> when (event) {
                ChunkEvent.Retry ->
                    if (retryCount < MAX_RETRIES) ChunkState.PENDING else ChunkState.INVALIDATED
                else -> current
            }

            ChunkState.INVALIDATED -> when (event) {
                ChunkEvent.SwitchSource -> ChunkState.PENDING
                else -> current
            }

            ChunkState.DONE -> when (event) {
                is ChunkEvent.HoleDetected -> ChunkState.PENDING
                else -> current
            }
        }
}
