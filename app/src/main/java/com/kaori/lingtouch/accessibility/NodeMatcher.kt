package com.kaori.lingtouch.accessibility

/** Declarative target selection. Never indexes into a stale tree by reference. */
data class NodeMatcher(
    val index: Int? = null,
    val text: String? = null,
    val resourceId: String? = null,
    val desc: String? = null,
    val contains: Boolean = true,
    val editableOnly: Boolean = false,
    val clickableOnly: Boolean = false,
    val scrollableOnly: Boolean = false
) {
    val isEmpty: Boolean get() = index == null && text.isNullOrBlank() && resourceId.isNullOrBlank() && desc.isNullOrBlank()

    companion object {
        fun fromNode(node: UiNode): NodeMatcher {
            val hasSemantic = node.text.isNotBlank() || node.resourceId.isNotBlank() || node.desc.isNotBlank()
            return if (hasSemantic) {
                NodeMatcher(
                    text = node.text.ifBlank { null },
                    resourceId = node.resourceId.ifBlank { null },
                    desc = node.desc.ifBlank { null }
                )
            } else {
                NodeMatcher(index = node.index)
            }
        }

        fun fromText(text: String): NodeMatcher = NodeMatcher(text = text)
    }
}

/** Result of one accessibility operation, including whether the target state was read back. */
data class OpResult(
    val success: Boolean,
    val message: String,
    val changed: Boolean = false,
    val readback: String = ""
)
