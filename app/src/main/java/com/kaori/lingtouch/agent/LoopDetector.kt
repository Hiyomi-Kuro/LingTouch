package com.kaori.lingtouch.agent

enum class LoopKind { REPEAT_ACTION, STAGNANT_SCREEN, OSCILLATION }

data class LoopSignal(val kind: LoopKind, val count: Int, val detail: String)

/**
 * Detects three different failure loops: repeated identical actions, a screen that never
 * changes, and page ping-pong. The logic is pure so it can be unit tested without a device.
 */
class LoopDetector(
    private val repeatThreshold: Int = 3,
    private val stagnantThreshold: Int = 4,
    private val oscillationWindow: Int = 6
) {
    private var lastAction: String = ""
    private var repeatCount: Int = 0
    private var lastScreen: String = ""
    private var stagnantCount: Int = 0
    private val screens = ArrayDeque<String>()

    fun onAction(signature: String): LoopSignal? {
        if (signature == lastAction) {
            repeatCount++
        } else {
            lastAction = signature
            repeatCount = 1
        }
        return if (repeatCount >= repeatThreshold) {
            LoopSignal(LoopKind.REPEAT_ACTION, repeatCount, "相同动作已连续执行 $repeatCount 次")
        } else {
            null
        }
    }

    fun onScreen(signature: String): LoopSignal? {
        if (signature.isNotBlank()) {
            if (signature == lastScreen) {
                stagnantCount++
            } else {
                lastScreen = signature
                stagnantCount = 0
            }
            if (signature != screens.lastOrNull()) {
                screens.addLast(signature)
                while (screens.size > oscillationWindow) screens.removeFirst()
            }
        }
        detectOscillation()?.let { return it }
        return if (stagnantCount >= stagnantThreshold) {
            LoopSignal(LoopKind.STAGNANT_SCREEN, stagnantCount, "界面连续 $stagnantCount 步没有变化")
        } else {
            null
        }
    }

    fun reset() {
        lastAction = ""
        repeatCount = 0
        lastScreen = ""
        stagnantCount = 0
        screens.clear()
    }

    fun snapshot(): String =
        "action=$lastAction repeat=$repeatCount stagnant=$stagnantCount screens=${screens.size}"

    private fun detectOscillation(): LoopSignal? {
        if (screens.size < 5) return null
        val tail = screens.toList()
        if (tail.distinct().size != 2) return null
        var alternations = 0
        for (index in 1 until tail.size) {
            if (tail[index] != tail[index - 1]) alternations++
        }
        return if (alternations >= tail.size - 1) {
            LoopSignal(LoopKind.OSCILLATION, alternations + 1, "界面在两个页面之间来回切换")
        } else {
            null
        }
    }
}
