package com.workspace.proot

import java.util.IdentityHashMap

/**
 * 浮窗请求归属登记：谁在屏上（current）、谁已经结算过（resolved）。
 *
 * termlou-ui 连续换屏错位修复的内核（治本）：
 *  - [takeOver] 换屏时把指针同步到新请求，并交还仍等待的旧请求 —— 由调用方给上一屏等待方写
 *    明确结果（dismiss），不再让上一屏干等到超时，也不再把本屏点击算到它头上；
 *  - [markResolved] 同一请求只允许结算一次 —— 陈旧回调（换屏前绑定的按钮/返回键/外部点击）
 *    重复触发时不再覆盖 res 文件、不再惊动别的请求；
 *  - [finish] 结算后按"仍是当前请求才清指针"收敛，避免把后来者的归属误清掉。
 *
 * 纯 JVM 实现（无 Android 依赖），由 EntryOwnershipTest 锁定换屏时序。
 */
class EntryOwnership<T> {

    private var cur: T? = null
    private val resolved = IdentityHashMap<T, Boolean>()

    /** 当前屏对应的请求。 */
    val current: T? get() = cur

    /** 该请求是否已结算过。 */
    fun isResolved(entry: T): Boolean = resolved.containsKey(entry)

    /** 标记结算；首次返回 true，重复结算返回 false（陈旧回调据此直接忽略）。 */
    fun markResolved(entry: T): Boolean = resolved.put(entry, true) == null

    /** 换屏接管：指针移到 [entry]，返回被顶掉且仍在等待的旧请求（没有则 null）。 */
    fun takeOver(entry: T): T? {
        val prev = cur
        cur = entry
        if (prev == null || prev === entry || resolved.containsKey(prev)) return null
        return prev
    }

    /** 请求结算后清指针（仅当它仍是当前请求）。 */
    fun finish(entry: T) {
        if (cur === entry) cur = null
    }

    /** 只清指针，保留结算标记。 */
    fun clearCurrent() {
        cur = null
    }

    /** 全清（会话拆除时用，结算标记一并作废）。 */
    fun reset() {
        cur = null
        resolved.clear()
    }
}
