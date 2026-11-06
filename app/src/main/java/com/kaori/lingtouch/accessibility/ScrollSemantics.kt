package com.kaori.lingtouch.accessibility

/** Direction semantics for scrolling, kept pure so it can be unit tested off-device. */
enum class ScrollAxis { BACKWARD, FORWARD, LEFT, RIGHT }

object ScrollSemantics {
    /**
     * up/backward scrolls toward earlier content (ACTION_SCROLL_BACKWARD);
     * down/forward scrolls toward later content (ACTION_SCROLL_FORWARD).
     */
    fun resolve(direction: String): ScrollAxis = when (direction.trim().lowercase()) {
        "up", "back", "backward", "top" -> ScrollAxis.BACKWARD
        "left" -> ScrollAxis.LEFT
        "right" -> ScrollAxis.RIGHT
        else -> ScrollAxis.FORWARD
    }

    fun describe(direction: String): String = when (resolve(direction)) {
        ScrollAxis.BACKWARD -> "向上滚动（查看更早内容）"
        ScrollAxis.FORWARD -> "向下滚动（查看更多内容）"
        ScrollAxis.LEFT -> "向左滚动"
        ScrollAxis.RIGHT -> "向右滚动"
    }
}
