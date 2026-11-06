package com.kaori.lingtouch.agent

import com.kaori.lingtouch.accessibility.UiTree

/** The only things that may count as completion evidence. Model prose never does. */
data class ExpectedState(
    val packageName: String = "",
    val activity: String = "",
    val text: String = "",
    val textAbsent: Boolean = false,
    val checkedText: String = "",
    val requireChangeFrom: String = "",
    val source: String = ""
) {
    val hasCondition: Boolean
        get() = packageName.isNotBlank() || activity.isNotBlank() || text.isNotBlank() ||
            checkedText.isNotBlank() || requireChangeFrom.isNotBlank()

    fun describe(): String = buildList {
        if (packageName.isNotBlank()) add("前台应用=$packageName")
        if (activity.isNotBlank()) add("Activity=$activity")
        if (text.isNotBlank()) add(if (textAbsent) "文字「$text」应不存在" else "文字「$text」应存在")
        if (checkedText.isNotBlank()) add("「$checkedText」应处于选中状态")
        if (requireChangeFrom.isNotBlank()) add("界面应发生变化")
    }.joinToString("；").ifBlank { "无可验证条件" }
}

enum class VerificationStatus { VERIFIED_SUCCESS, VERIFIED_FAILED, UNKNOWN }

data class Verification(
    val status: VerificationStatus,
    val detail: String,
    val expected: ExpectedState
) {
    val verified: Boolean get() = status == VerificationStatus.VERIFIED_SUCCESS
}

/**
 * Independent verifier: it reads the live accessibility snapshot and never trusts the
 * model evidence string. Missing data yields UNKNOWN, never success.
 */
object Verifier {

    fun verify(expected: ExpectedState, tree: UiTree?, current: ScreenState?): Verification {
        if (!expected.hasCondition) {
            return Verification(VerificationStatus.UNKNOWN, "没有可独立验证的完成条件", expected)
        }
        val notes = mutableListOf<String>()
        var failed = false
        var unknown = false

        val actualPackage = tree?.packageName?.takeIf { it.isNotBlank() }
            ?: current?.packageName?.takeIf { it.isNotBlank() }.orEmpty()
        if (expected.packageName.isNotBlank()) {
            when {
                actualPackage.isBlank() -> {
                    unknown = true
                    notes.add("无法确认前台应用（期望 ${expected.packageName}）")
                }
                !actualPackage.equals(expected.packageName, ignoreCase = true) -> {
                    failed = true
                    notes.add("前台应用是 $actualPackage，不是 ${expected.packageName}")
                }
                else -> notes.add("前台应用为 $actualPackage")
            }
        }

        if (expected.activity.isNotBlank()) {
            val actualActivity = tree?.activityName?.takeIf { it.isNotBlank() }
                ?: current?.activityName.orEmpty()
            when {
                actualActivity.isBlank() -> {
                    unknown = true
                    notes.add("无法确认当前 Activity（期望 ${expected.activity}）")
                }
                !activityMatches(actualActivity, expected.activity) -> {
                    failed = true
                    notes.add(
                        "当前界面是 ${actualActivity.substringAfterLast('.')}，不是 " +
                            expected.activity.substringAfterLast('.')
                    )
                }
                else -> notes.add("已进入 ${expected.activity.substringAfterLast('.')}")
            }
        }

        if (expected.requireChangeFrom.isNotBlank()) {
            val actualSignature = current?.signature?.takeIf { it.isNotBlank() }
                ?: ScreenState.from(tree, accessibility = tree != null).signature
            when {
                actualSignature.isBlank() -> {
                    unknown = true
                    notes.add("无法确认界面是否发生变化")
                }
                actualSignature == expected.requireChangeFrom -> {
                    failed = true
                    notes.add("界面没有发生变化")
                }
                else -> notes.add("界面已发生变化")
            }
        }

        if (expected.text.isNotBlank() || expected.checkedText.isNotBlank()) {
            if (tree == null) {
                unknown = true
                notes.add("无法读取界面节点，不能校验文字或控件状态")
            } else {
                if (expected.text.isNotBlank()) {
                    val found = tree.nodes.any { it.matchesText(expected.text, contains = true) }
                    if (expected.textAbsent) {
                        if (found) {
                            failed = true
                            notes.add("界面仍存在「${expected.text}」")
                        } else {
                            notes.add("界面已没有「${expected.text}」")
                        }
                    } else if (found) {
                        notes.add("界面存在「${expected.text}」")
                    } else {
                        failed = true
                        notes.add("界面中未找到「${expected.text}」")
                    }
                }
                if (expected.checkedText.isNotBlank()) {
                    val node = tree.nodes.firstOrNull { it.matchesText(expected.checkedText, contains = true) }
                    when {
                        node == null -> {
                            unknown = true
                            notes.add("界面中未找到「${expected.checkedText}」，无法确认其选中状态")
                        }
                        node.checked || node.selected -> notes.add("「${expected.checkedText}」已选中")
                        else -> {
                            failed = true
                            notes.add("「${expected.checkedText}」未选中")
                        }
                    }
                }
            }
        }

        val status = when {
            failed -> VerificationStatus.VERIFIED_FAILED
            unknown -> VerificationStatus.UNKNOWN
            else -> VerificationStatus.VERIFIED_SUCCESS
        }
        val detail = notes.joinToString("；").ifBlank { expected.describe() }
        return Verification(status, detail, expected)
    }

    /** Combines the model terminal expectation with the current sub-goal requirement. */
    fun merge(primary: ExpectedState, secondary: ExpectedState?): ExpectedState {
        if (secondary == null) return primary
        return ExpectedState(
            packageName = primary.packageName.ifBlank { secondary.packageName },
            activity = primary.activity.ifBlank { secondary.activity },
            text = primary.text.ifBlank { secondary.text },
            textAbsent = primary.textAbsent,
            checkedText = primary.checkedText.ifBlank { secondary.checkedText },
            requireChangeFrom = primary.requireChangeFrom.ifBlank { secondary.requireChangeFrom },
            source = listOf(primary.source, secondary.source).filter { it.isNotBlank() }.joinToString("+")
        )
    }

    private fun activityMatches(actual: String, expected: String): Boolean {
        if (actual.equals(expected, ignoreCase = true)) return true
        if (actual.endsWith(".$expected", ignoreCase = true)) return true
        if (actual.contains(expected, ignoreCase = true)) return true
        return actual.substringAfterLast('.') == expected.substringAfterLast('.')
    }
}
