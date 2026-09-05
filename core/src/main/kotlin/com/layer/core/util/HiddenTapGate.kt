package com.layer.core.util

class HiddenTapGate(
    private val required: Int = 7,
    private val windowMs: Long = 2_500L,
) {
    private var count = 0
    private var lastElapsed: Long? = null

    val progress: Int get() = count

    fun tap(nowElapsed: Long): HiddenTapResult {
        val previous = lastElapsed
        if (previous == null || nowElapsed - previous > windowMs) {
            count = 0
        }
        lastElapsed = nowElapsed
        count += 1
        if (count >= required) {
            count = 0
            lastElapsed = null
            return HiddenTapResult.Unlocked
        }
        return HiddenTapResult.Progress(count = count, remaining = required - count)
    }

    fun reset() {
        count = 0
        lastElapsed = null
    }
}

sealed class HiddenTapResult {
    data object Unlocked : HiddenTapResult()
    data class Progress(val count: Int, val remaining: Int) : HiddenTapResult()
}
