package com.mzstd.hxmyproxy.core.rules

/**
 * 域名字典树（标签反转插入，匹配 O(标签数)）。三种作用域，由**写法**决定：
 * - [addSuffix]：`example.com` —— 自身 + **任意深度**子域（内置表 6.6 万条全是这一档）
 * - [addSingle]：`*.example.com` —— 自身 + **恰好一级**子域（`a.example.com` 中，`a.b.example.com` 不中）
 * - [addExact]：`=example.com` —— **仅**自身
 *
 * 域名一律小写、去首尾空白与尾点后按 '.' 切分；非法（空标签）忽略。
 * 非线程安全：构建（add*）阶段单线程完成后，作为不可变快照交给 [RuleEngine] 只读匹配。
 */
class DomainSuffixSet {
    private class Node {
        val children = HashMap<String, Node>()
        var suffixEnd = false   // 命中此后缀的自身及一切子域
        var singleEnd = false   // 命中自身 + 恰好一级子域
        var exactEnd = false    // 仅精确命中
    }

    private val root = Node()
    var size: Int = 0
        private set

    /** `example.com` —— 匹配自身及任意深度子域。 */
    fun addSuffix(domain: String) {
        val node = walkCreate(domain) ?: return
        if (!node.suffixEnd) { node.suffixEnd = true; size++ }
    }

    /** `*.example.com` —— 匹配自身及**恰好一级**子域。 */
    fun addSingle(domain: String) {
        val node = walkCreate(domain) ?: return
        if (!node.singleEnd) { node.singleEnd = true; size++ }
    }

    /** `=example.com` —— 仅匹配自身。 */
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

    /** host 是否命中本集合（任一作用域）。 */
    fun matches(host: String): Boolean = matchDepth(host) >= 0

    /**
     * 返回命中规则**锚定域名的标签数**（越大越具体），未命中返回 -1。
     *
     * 供 most-specific-wins 裁决：同一 host 可能同时命中 `apple.com`(锚定 2 级) 与
     * `xxx.apple.com`(锚定 3 级)，取更深的那条 —— 这样「先加泛规则、后加具体规则」时
     * 具体的那条自然胜出，而不必依赖添加顺序。
     */
    fun matchDepth(host: String): Int {
        val labels = normalize(host) ?: return -1
        var node = root
        var best = -1
        for (i in labels.indices.reversed()) {
            node = node.children[labels[i]] ?: return best
            val anchorLabels = labels.size - i    // 已走过的标签数 = 锚定域名有多长
            val remaining = i                     // host 还剩几级挂在锚定域名之下
            // suffix：底下多少级都算；single：最多再一级；exact：必须刚好走完
            if (node.suffixEnd ||
                (node.singleEnd && remaining <= 1) ||
                (node.exactEnd && remaining == 0)
            ) {
                if (anchorLabels > best) best = anchorLabels
            }
        }
        return best
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
