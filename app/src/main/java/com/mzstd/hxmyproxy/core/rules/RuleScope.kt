package com.mzstd.hxmyproxy.core.rules

/**
 * 规则的**作用域**——一条规则往下管多深。由规则文本的前缀决定，三档：
 *
 * | 写法 | 档位 | `apple.com` | `xx.apple.com` | `xx.yy.apple.com` |
 * |---|---|---|---|---|
 * | `apple.com`   | [SUFFIX] 全层级 | ✅ | ✅ | ✅ |
 * | `*.apple.com` | [SINGLE] 单级   | ✅ | ✅ | ❌ |
 * | `=apple.com`  | [EXACT] 精确    | ✅ | ❌ | ❌ |
 *
 * **无前缀＝全层级**是历史语义，必须保持：内置 6.6 万条（广告表 + 65 个 App 组）全是这个写法，
 * 靠它让 `ad.qq.com` 这类条目也能覆盖到更深层子域。新增的两档是纯增量，老数据零迁移。
 *
 * [specificity] 用于同一 host 命中多条时的裁决（most-specific-wins）：先比锚定域名的标签数，
 * 标签数相同再比档位。例如 host=`xxx.apple.com` 同时命中 `*.apple.com`(锚定 2 级) 与
 * `xxx.apple.com`(锚定 3 级) 时，后者胜出，与添加先后无关。
 */
enum class RuleScope(val prefix: String) {
    /** `example.com` —— 自身 + 任意深度子域。 */
    SUFFIX(""),
    /** `*.example.com` —— 自身 + 恰好一级子域。 */
    SINGLE("*."),
    /** `=example.com` —— 仅自身。 */
    EXACT("=");

    /** 同锚定长度下的具体度权重：精确 > 单级 > 全层级。 */
    val specificity: Int get() = when (this) {
        SUFFIX -> 0
        SINGLE -> 1
        EXACT -> 2
    }

    companion object {
        /**
         * 从规则文本解析出 (作用域, 裸域名)。无法识别前缀时按 [SUFFIX]（兼容历史数据）。
         * IP/CIDR 不参与作用域概念，调用方需先行分派。
         */
        fun parse(rule: String): Pair<RuleScope, String> {
            val r = rule.trim()
            return when {
                r.startsWith("=") -> EXACT to r.removePrefix("=").trim()
                r.startsWith("*.") -> SINGLE to r.removePrefix("*.").trim()
                else -> SUFFIX to r
            }
        }

        /** 反向：把 (作用域, 裸域名) 拼回存储/展示用的规则文本。 */
        fun format(scope: RuleScope, bare: String): String = scope.prefix + bare
    }
}
