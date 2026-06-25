package com.mzstd.hxmyproxy.core.rules

/**
 * 域名后缀字典树（标签反转插入，匹配 O(标签数)）。
 * - [addSuffix]：DOMAIN-SUFFIX 语义，匹配该域名自身及其所有子域。
 * - [addExact]：DOMAIN 语义，仅精确匹配该域名。
 *
 * 域名一律小写、去首尾空白与尾点后按 '.' 切分；非法（空标签）忽略。
 * 非线程安全：构建（add*）阶段单线程完成后，作为不可变快照交给 [RuleEngine] 只读匹配。
 */
class DomainSuffixSet {
    private class Node {
        val children = HashMap<String, Node>()
        var suffixEnd = false   // 命中此后缀的自身及一切子域
        var exactEnd = false    // 仅精确命中
    }

    private val root = Node()
    var size: Int = 0
        private set

    /** DOMAIN-SUFFIX,domain —— 匹配 domain 及 *.domain。 */
    fun addSuffix(domain: String) {
        val node = walkCreate(domain) ?: return
        if (!node.suffixEnd) { node.suffixEnd = true; size++ }
    }

    /** DOMAIN,domain —— 仅匹配 domain 自身。 */
    fun addExact(domain: String) {
        val node = walkCreate(domain) ?: return
        if (!node.exactEnd) { node.exactEnd = true; size++ }
    }

    private fun walkCreate(domain: String): Node? {
        val labels = normalize(domain) ?: return null
        var node = root
        for (i in labels.indices.reversed()) {
            node = node.children.getOrPut(labels[i]) { Node() }
        }
        return node
    }

    /** host 是否命中本集合（后缀或精确）。 */
    fun matches(host: String): Boolean {
        val labels = normalize(host) ?: return false
        var node = root
        for (i in labels.indices.reversed()) {
            node = node.children[labels[i]] ?: return false
            if (node.suffixEnd) return true   // 到达某后缀末端：host 等于或为其子域
        }
        return node.exactEnd                  // host 标签用尽：仅当精确标记
    }

    companion object {
        /** 小写、去首尾空白与尾点，按 '.' 切分；空或含空标签返回 null。 */
        fun normalize(host: String): List<String>? {
            val h = host.trim().lowercase().trimEnd('.')
            if (h.isEmpty()) return null
            val labels = h.split('.')
            if (labels.any { it.isEmpty() }) return null
            return labels
        }
    }
}
