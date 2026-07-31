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
 * ## 档位只决定作用范围，**不参与优先级裁决**
 *
 * 这两件事是正交的：档位回答「这条规则管哪些 host」，裁决回答「多条都管到时听谁的」。
 * 裁决只看**锚定域名的标签数**（见 [DomainSuffixSet.matchDepth]）：锚定越深＝用户指定得越具体。
 * 例如 host=`xxx.apple.com` 同时命中 `*.apple.com`(锚定 2 级) 与 `xxx.apple.com`(锚定 3 级)，
 * 后者胜出，与添加先后无关。
 *
 * 而档位不同、锚定相同的几条（`example.com` / `*.example.com` / `=example.com`）对
 * host=`example.com` 是**平手**——它们对「哪个域」的指定精度本来就一样，差别只在管不管子域。
 * 平手时按「谁是用户最近的意图」裁决（见 [RuleEngine.Snapshot.userDirectNewer]）。
 *
 * 曾有过一个 `specificity` 属性想让「精确 > 单级 > 全层级」参与裁决，但它从未被任何代码调用，
 * 且与上述模型冲突（档位不是优先级），已删除。UI 文案（「仅此域名 / 下一级 / 所有层级」、
 * 「作用范围」）一直只讲范围、从未承诺优先级，这里与产品口径对齐。
 */
enum class RuleScope(val prefix: String) {
    /** `example.com` —— 自身 + 任意深度子域。 */
    SUFFIX(""),
    /** `*.example.com` —— 自身 + 恰好一级子域。 */
    SINGLE("*."),
    /** `=example.com` —— 仅自身。 */
    EXACT("=");

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
