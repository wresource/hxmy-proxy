package com.mzstd.hxmyproxy.data.repository

import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.HostRuleState
import com.mzstd.hxmyproxy.core.rules.RecentRuleChange
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleChangeJournal
import com.mzstd.hxmyproxy.core.rules.RuleInput
import org.json.JSONArray
import org.json.JSONObject

/** Only host-scoped snapshots are accepted; a backup must not inject unrelated rules through undo. */
internal object RuleChangeJournalCodec {
    private const val MAX_BYTES = 1_048_576

    fun encode(journal: RuleChangeJournal): String {
        val entries = JSONArray()
        journal.changes.forEach { change ->
            entries.put(JSONObject().put("id", change.id).put("host", change.host).put("rule", change.rule)
                .put("action", change.action?.name ?: JSONObject.NULL).put("at", change.at)
                .put("reverted", change.reverted).put("beforeVersion", change.beforeVersion ?: JSONObject.NULL)
                .put("before", state(change.before)).put("after", state(change.after)))
        }
        return JSONObject().put("format", 1).put("changes", entries)
            .put("versions", JSONObject(journal.versions)).toString().also {
                require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Rule change history is too large" }
            }
    }

    fun decode(raw: String?): RuleChangeJournal {
        if (raw == null) return RuleChangeJournal()
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Rule change history is too large" }
        val root = JSONObject(raw)
        require(root.getInt("format") == 1) { "Unsupported rule change history" }
        val array = root.getJSONArray("changes")
        require(array.length() <= RuleChangeJournal.LIMIT) { "Too many rule changes" }
        val changes = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val host = obj.getString("host")
            val rule = obj.getString("rule")
            require(RuleInput.parse(rule)?.host == host) { "Rule change host mismatch" }
            val at = obj.getLong("at"); require(at >= 0)
            RecentRuleChange(id(obj.getString("id")), host, rule,
                if (obj.isNull("action")) null else RuleAction.valueOf(obj.getString("action")),
                readState(obj.getJSONObject("before"), host), readState(obj.getJSONObject("after"), host), at,
                obj.getBoolean("reverted"), if (obj.isNull("beforeVersion")) null else id(obj.getString("beforeVersion")))
        }
        require(changes.map { it.id }.distinct().size == changes.size) { "Duplicate rule change IDs" }
        val hosts = changes.mapTo(hashSetOf()) { it.host }
        val versions = root.getJSONObject("versions")
        val map = linkedMapOf<String, String>()
        versions.keys().forEach { host ->
            require(host in hosts) { "Unexpected rule history host" }
            map[host] = id(versions.getString(host))
        }
        return RuleChangeJournal(changes, map)
    }

    fun readLocal(raw: String?): RuleChangeJournal = runCatching { decode(raw) }
        .getOrElse { RuleChangeJournal(unreadable = true) }

    private fun id(value: String): String = value.also { require(it.isNotBlank() && it.length <= 80) { "Invalid rule change ID" } }
    private fun state(value: HostRuleState): JSONObject = JSONObject()
        .put("direct", rules(value.direct)).put("reject", rules(value.reject))
        .put("overrides", JSONObject(value.overrides.mapValues { it.value.name }))
    private fun rules(entries: List<RuleEntry>): JSONArray = JSONArray().apply {
        entries.forEach { put(JSONObject().put("value", it.value).put("enabled", it.enabled)
            .put("addedAt", it.addedAt).put("disabledAt", it.disabledAt)) }
    }
    private fun readState(obj: JSONObject, host: String): HostRuleState {
        fun check(value: String): String = value.also {
            require(RuleInput.parse(it)?.host == host) { "Snapshot contains another host or an invalid rule" }
        }
        fun readRules(key: String): List<RuleEntry> {
            val array = obj.getJSONArray(key)
            val result = (0 until array.length()).map { i ->
                val r = array.getJSONObject(i)
                RuleEntry(check(r.getString("value")), r.getBoolean("enabled"), r.getLong("addedAt"), r.getLong("disabledAt"))
            }
            require(result.map { it.value }.distinct().size == result.size) { "Duplicate snapshot rules" }
            return result
        }
        val overrides = obj.getJSONObject("overrides")
        val values = linkedMapOf<String, RuleAction>()
        overrides.keys().forEach { key -> values[check(key)] = RuleAction.valueOf(overrides.getString(key)) }
        return HostRuleState(readRules("direct"), readRules("reject"), values)
    }
}
