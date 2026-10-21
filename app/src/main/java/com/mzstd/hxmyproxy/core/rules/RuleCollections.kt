package com.mzstd.hxmyproxy.core.rules

/** Lets immutable disk collections keep legacy rule semantics without materializing whole lists. */
interface RebuildableRuleList<T> : List<T> {
    fun rebuild(values: Sequence<T>): List<T>
    fun forHost(host: String): List<T>
}
interface RebuildableRuleMap : Map<String, RuleAction> {
    fun rebuild(values: Sequence<Pair<String, RuleAction>>): Map<String, RuleAction>
    fun forHost(host: String): Map<String, RuleAction>
}
fun <T> List<T>.ruleRebuild(values: Sequence<T>): List<T> =
    if (this is RebuildableRuleList<T>) rebuild(values) else values.toList()
fun <T> List<T>.ruleMap(transform: (T) -> T): List<T> = ruleRebuild(asSequence().map(transform))
fun <T> List<T>.ruleFilterNot(predicate: (T) -> Boolean): List<T> = ruleRebuild(asSequence().filterNot(predicate))
fun <T> List<T>.rulePlus(value: T): List<T> = ruleRebuild(asSequence() + value)
fun Map<String, RuleAction>.ruleRebuild(values: Sequence<Pair<String, RuleAction>>): Map<String, RuleAction> =
    if (this is RebuildableRuleMap) rebuild(values) else values.toMap()
fun Map<String, RuleAction>.ruleFilterKeys(predicate: (String) -> Boolean): Map<String, RuleAction> =
    ruleRebuild(asSequence().filter { predicate(it.key) }.map { it.key to it.value })
