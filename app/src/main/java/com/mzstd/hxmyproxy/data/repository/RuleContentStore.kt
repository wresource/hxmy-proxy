package com.mzstd.hxmyproxy.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.IpRange
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleInput
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.core.rules.RebuildableRuleList
import com.mzstd.hxmyproxy.core.rules.RebuildableRuleMap
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Immutable, indexed rule collections. DataStore publishes a reference only after finish().
 * Interrupted writes stay invisible. Old collections remain available to readers and undo.
 * Nothing here changes routing precedence or performs DNS/network access.
 */
class RuleContentStore private constructor(context: Context) {
    data class Ref(val id: String, val kind: Int, val size: Int, val hash: Int)
    data class Row(val value: String, val enabled: Boolean = true, val addedAt: Long = 0,
        val disabledAt: Long = 0, val action: RuleAction? = null, val ordinal: Int = 0,
        val collection: String = "", val binding: String = "") {
        fun entry() = RuleEntry(value, enabled, addedAt, disabledAt)
    }
    data class Page(val rows: List<Row>, val total: Int, val offset: Int, val limit: Int)
    private data class Pending(val row: Row, val preserve: Boolean)
    private val helper = object : SQLiteOpenHelper(context.applicationContext ?: context,
        File(context.noBackupFilesDir, "rules-content.db").absolutePath, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE collections(id TEXT PRIMARY KEY,kind INTEGER NOT NULL,size INTEGER NOT NULL DEFAULT 0,hash INTEGER NOT NULL DEFAULT 1,complete INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE rules(collection TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,value TEXT NOT NULL,enabled INTEGER NOT NULL,added INTEGER NOT NULL,disabled INTEGER NOT NULL,action TEXT,canonical TEXT NOT NULL,anchor TEXT NOT NULL,scope TEXT,ip_bits INTEGER NOT NULL,ip_start TEXT,ip_end TEXT,reverse_anchor TEXT NOT NULL,depth INTEGER NOT NULL,PRIMARY KEY(collection,ordinal))")
            db.execSQL("CREATE INDEX rule_value ON rules(collection,canonical)")
            db.execSQL("CREATE INDEX rule_exact ON rules(collection,value)")
            db.execSQL("CREATE INDEX rule_ip ON rules(collection,ip_bits,ip_start,ip_end)")
            db.execSQL("CREATE INDEX rule_anchor ON rules(collection,anchor)")
            db.execSQL("CREATE INDEX rule_descendants ON rules(collection,reverse_anchor)")
            db.execSQL("CREATE TABLE ip_segments(collection TEXT NOT NULL,ordinal INTEGER NOT NULL,bits INTEGER NOT NULL,start TEXT NOT NULL,end TEXT NOT NULL,prefix_key TEXT NOT NULL,FOREIGN KEY(collection,ordinal) REFERENCES rules(collection,ordinal) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX ip_ancestors ON ip_segments(collection,prefix_key)")
            db.execSQL("CREATE INDEX ip_descendants ON ip_segments(collection,bits,start)")
            db.execSQL("CREATE TABLE staging_seen(collection TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,value TEXT NOT NULL,PRIMARY KEY(collection,value))")
            db.execSQL("CREATE TABLE asset_collections(asset TEXT PRIMARY KEY,collection TEXT NOT NULL REFERENCES collections(id))")
            db.execSQL("CREATE TABLE import_jobs(id TEXT PRIMARY KEY,updated INTEGER NOT NULL,json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE import_issues(job TEXT NOT NULL,line INTEGER NOT NULL,value TEXT NOT NULL,reason TEXT NOT NULL,PRIMARY KEY(job,line))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported rule store version")
    }.apply { setWriteAheadLoggingEnabled(true) }
    private val db: SQLiteDatabase get() = helper.writableDatabase
    private val readers = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, java.lang.ref.WeakReference<Any>>>()
    private val retentionLock = Any()
    private val retained = java.util.concurrent.atomic.AtomicInteger()
    private fun retain(id: String, owner: Any) {
        synchronized(retentionLock) { readers += id to java.lang.ref.WeakReference(owner) }
        if (retained.incrementAndGet() % 512 == 0) readers.removeIf { it.second.get() == null }
    }
    private val countCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Map<String, Int>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Int>>?) = size > 128
    })

    fun writer(kind: Int = STRINGS, deduplicate: Boolean = false): Writer = Writer(kind, deduplicate)

    inner class Writer internal constructor(private val kind: Int, private val deduplicate: Boolean) : AutoCloseable {
        val id = UUID.randomUUID().toString()
        private var count = 0
        private var hash = if (kind == OVERRIDES) 0 else 1
        private var finished = false
        private var closed = false
        private var failed = false
        var duplicates: Int = 0
            private set
        private val pending = ArrayList<Pending>(BATCH)
        init { require(kind in STRINGS..OVERRIDES); retain(id, this); db.execSQL("INSERT INTO collections(id,kind,created) VALUES(?,?,?)", arrayOf<Any>(id, kind, System.currentTimeMillis())) }

        fun add(row: Row) = enqueue(row, false)
        /** Appending must not deduplicate/reprioritize old entries with different timestamps or switches. */
        fun addExisting(row: Row) = enqueue(row, true)
        private fun enqueue(row: Row, preserve: Boolean) { check(!closed && !finished && !failed); pending += Pending(row, preserve); if (pending.size >= BATCH) flush() }
        fun flush() {
            check(!closed && !finished && !failed)
            if (pending.isEmpty()) return
            val database = db
            try { database.transaction {
                database.compileStatement("INSERT INTO rules VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
                    pending.forEach { item ->
                        val row = item.row
                        val parsed = RuleInput.parse(row.value)
                        val range = if (parsed?.isIp == true && !parsed.fromUrl) IpRange.ofRule(parsed.host) else null
                        if (deduplicate) {
                            val duplicateKey = parsed?.takeUnless { it.fromUrl }?.let(RuleInput::equivalentKey) ?: row.value
                            val inserted = database.insertWithOnConflict("staging_seen", null, ContentValues().apply {
                                put("collection", id); put("value", duplicateKey)
                            }, SQLiteDatabase.CONFLICT_IGNORE)
                            if (inserted == -1L && !item.preserve) { duplicates++; return@forEach }
                        }
                        if (kind == OVERRIDES && !deduplicate) {
                            val old = database.rawQuery("SELECT ordinal,action FROM rules WHERE collection=? AND value=? LIMIT 1", arrayOf(id, row.value)).use {
                                if (it.moveToFirst()) it.getInt(0) to RuleAction.valueOf(it.getString(1)) else null
                            }
                            if (old != null) {
                                val action = requireNotNull(row.action)
                                database.execSQL("UPDATE rules SET action=?,enabled=?,added=?,disabled=? WHERE collection=? AND ordinal=?",
                                    arrayOf<Any>(action.name, if (row.enabled) 1 else 0, row.addedAt, row.disabledAt, id, old.first))
                                hash += (row.value.hashCode() xor action.hashCode()) - (row.value.hashCode() xor old.second.hashCode())
                                return@forEach
                            }
                        }
                        statement.clearBindings()
                        statement.bindString(1, id); statement.bindLong(2, count.toLong()); statement.bindString(3, row.value)
                        statement.bindLong(4, if (row.enabled) 1 else 0); statement.bindLong(5, row.addedAt); statement.bindLong(6, row.disabledAt)
                        row.action?.let { statement.bindString(7, it.name) }
                        statement.bindString(8, parsed?.value ?: row.value)
                        statement.bindString(9, parsed?.host ?: row.value)
                        parsed?.takeUnless { it.fromUrl }?.scope?.let { statement.bindString(10, it.name) }
                        statement.bindLong(11, range?.bits?.toLong() ?: 0)
                        range?.let { statement.bindString(12, it.startKey); statement.bindString(13, it.endKey) }
                        statement.bindString(14, (parsed?.host ?: row.value).reversed())
                        statement.bindLong(15, (parsed?.host?.count { it == '.' }?.plus(1) ?: 0).toLong())
                        statement.executeInsert()
                        if (range != null) database.compileStatement("INSERT INTO ip_segments VALUES(?,?,?,?,?,?)").use { segment ->
                            range.cidrs().forEach { cidr ->
                                val part = requireNotNull(IpRange.ofRule(cidr)); val prefix = cidr.substringAfterLast('/').toInt()
                                segment.bindString(1, id); segment.bindLong(2, count.toLong()); segment.bindLong(3, part.bits.toLong())
                                segment.bindString(4, part.startKey); segment.bindString(5, part.endKey)
                                segment.bindString(6, "${part.bits}:$prefix:${part.startKey}"); segment.executeInsert()
                            }
                        }
                        hash = when (kind) {
                            ENTRIES -> 31 * hash + row.entry().hashCode()
                            OVERRIDES -> hash + (row.value.hashCode() xor requireNotNull(row.action).hashCode())
                            else -> 31 * hash + row.value.hashCode()
                        }
                        count++
                    }
                }
            } } catch (error: Throwable) { failed = true; throw error }
            pending.clear()
        }

        fun finish(): Ref {
            flush()
            db.transaction {
                db.execSQL("UPDATE collections SET size=?,hash=?,complete=1 WHERE id=?", arrayOf<Any>(count, hash, id))
                db.delete("staging_seen", "collection=?", arrayOf(id))
            }
            finished = true
            return Ref(id, kind, count, hash).also { retain(id, it) }
        }
        override fun close() {
            if (closed) return
            closed = true; pending.clear()
            if (!finished) db.delete("collections", "id=? AND complete=0", arrayOf(id))
        }
    }

    fun persistStrings(values: Sequence<String>): List<String> = writer().use { w ->
        values.forEach { w.add(Row(it)) }; strings(w.finish())
    }
    fun persistEntries(values: Sequence<RuleEntry>): List<RuleEntry> = writer(ENTRIES).use { w ->
        values.forEach { w.add(Row(it.value, it.enabled, it.addedAt, it.disabledAt)) }; entries(w.finish())
    }
    fun persistOverrides(values: Sequence<Pair<String, RuleAction>>): Map<String, RuleAction> = writer(OVERRIDES).use { w ->
        values.forEach { (value, action) -> w.add(Row(value, action = action)) }; overrides(w.finish())
    }

    fun ref(id: String): Ref? = db.rawQuery("SELECT kind,size,hash FROM collections WHERE id=? AND complete=1", arrayOf(id)).use {
        if (!it.moveToFirst()) null else Ref(id, it.getInt(0), it.getInt(1), it.getInt(2))
    }
    private fun verify(ref: Ref) {
        if (this.ref(ref.id) != ref) throw IOException("Rule collection unavailable or incomplete: ${ref.id}")
    }
    fun strings(ref: Ref): List<String> { require(ref.kind == STRINGS); return StoredList(this, ref) { it.value } }
    fun entries(ref: Ref): List<RuleEntry> { require(ref.kind == ENTRIES); return StoredList(this, ref) { it.entry() } }
    fun overrides(ref: Ref): Map<String, RuleAction> { require(ref.kind == OVERRIDES); return StoredMap(this, ref) }

    fun query(ref: Ref, query: String = "", type: Int? = null, offset: Int = 0, limit: Int = PAGE): Page {
        require(offset >= 0 && limit in 1..PAGE)
        verify(ref)
        val where = StringBuilder("collection=?")
        val args = mutableListOf(ref.id)
        if (query.isNotBlank()) { where.append(" AND instr(lower(value),lower(?))>0"); args += query.trim() }
        if (type != null) { where.append(" AND ip_bits=?"); args += type.toString() }
        val count = if (query.isBlank() && type == null) ref.size else db.rawQuery("SELECT COUNT(*) FROM rules WHERE $where", args.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
        val rows = db.rawQuery("SELECT $COLUMNS FROM rules WHERE $where ORDER BY ordinal LIMIT ? OFFSET ?", (args + limit.toString() + offset.toString()).toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.row()) } }
        return Page(rows, count, offset, limit)
    }

    fun queryMany(bindings: List<Pair<String, Ref>>, query: String = "", offset: Int = 0, limit: Int = PAGE,
        actions: Map<String, RuleAction> = emptyMap(), filter: RuleAction? = null): Page {
        require(offset >= 0 && limit in 1..PAGE)
        if (bindings.isEmpty()) return Page(emptyList(), 0, offset, limit)
        bindings.forEach { verify(it.second) }
        val args = mutableListOf<String>()
        val union = bindings.joinToString(" UNION ALL ") { (binding, ref) ->
            args += binding; args += ref.id
            if (query.isNotBlank()) args += query.trim()
            if (filter != null) args += listOf((actions[binding] ?: RuleAction.PROXY).name, filter.name)
            "SELECT $COLUMNS,? AS binding FROM rules WHERE collection=?" +
                (if (query.isBlank()) "" else " AND instr(lower(value),lower(?))>0") +
                (if (filter == null) "" else " AND COALESCE(action,?)=?")
        }
        val count = db.rawQuery("SELECT COUNT(*) FROM ($union)", args.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
        val rows = db.rawQuery("SELECT * FROM ($union) ORDER BY enabled DESC,CASE WHEN enabled=1 THEN added ELSE -disabled END,binding,ordinal LIMIT ? OFFSET ?",
            (args + limit.toString() + offset.toString()).toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.row()) } }
        return Page(rows, count, offset, limit)
    }

    fun counts(ref: Ref): Map<String, Int> {
        countCache[ref.id]?.let { return it }
        verify(ref)
        return db.rawQuery("SELECT ip_bits,COUNT(*) FROM rules WHERE collection=? GROUP BY ip_bits", arrayOf(ref.id)).use { c -> buildMap {
            while (c.moveToNext()) put(when (c.getInt(0)) { 32 -> "ipv4"; 128 -> "ipv6"; else -> "domain" }, c.getInt(1))
        } }.also { countCache[ref.id] = it }
    }
    fun saveJob(id: String, json: String) { db.execSQL("INSERT OR REPLACE INTO import_jobs VALUES(?,?,?)", arrayOf<Any>(id, System.currentTimeMillis(), json)) }
    fun jobs(): List<String> = db.rawQuery("SELECT json FROM import_jobs ORDER BY updated DESC LIMIT 20", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    fun job(id: String): String? = db.rawQuery("SELECT json FROM import_jobs WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
    data class Issue(val line: Int, val value: String, val reason: String)
    fun addIssues(job: String, issues: List<Issue>) {
        if (issues.isEmpty()) return
        db.transaction {
            db.compileStatement("INSERT INTO import_issues VALUES(?,?,?,?)").use { s -> issues.forEach {
                s.bindString(1, job); s.bindLong(2, it.line.toLong()); s.bindString(3, it.value); s.bindString(4, it.reason); s.executeInsert()
            } }
        }
    }
    fun issues(job: String, offset: Int = 0, limit: Int = PAGE): List<Issue> = db.rawQuery(
        "SELECT line,value,reason FROM import_issues WHERE job=? ORDER BY line LIMIT ? OFFSET ?",
        arrayOf(job, limit.coerceIn(1, PAGE).toString(), offset.coerceAtLeast(0).toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(Issue(c.getInt(0), c.getString(1), c.getString(2))) } }

    /** Rows are fetched in bounded pages; iterators never leave an open Cursor behind. */
    fun rows(ref: Ref): Sequence<Row> = sequence {
        var offset = 0
        while (offset < ref.size) {
            val page = query(ref, offset = offset)
            if (page.rows.isEmpty()) throw IOException("Truncated rule collection: ${ref.id}")
            yieldAll(page.rows)
            offset += page.rows.size
        }
    }
    fun lookup(ref: Ref, value: String): Row? {
        verify(ref)
        return db.rawQuery("SELECT $COLUMNS FROM rules WHERE collection=? AND canonical=? ORDER BY ordinal LIMIT 1", arrayOf(ref.id, RuleInput.normalize(value) ?: value)).use { if (it.moveToFirst()) it.row() else null }
    }
    private fun exact(ref: Ref, value: String): Row? {
        verify(ref)
        return db.rawQuery("SELECT $COLUMNS FROM rules WHERE collection=? AND value=? ORDER BY ordinal LIMIT 1", arrayOf(ref.id, value)).use { if (it.moveToFirst()) it.row() else null }
    }
    private fun forHost(ref: Ref, host: String): List<Row> {
        verify(ref)
        return db.rawQuery("SELECT $COLUMNS FROM rules WHERE collection=? AND anchor=? ORDER BY ordinal", arrayOf(ref.id, host)).use { c -> buildList { while (c.moveToNext()) add(c.row()) } }
    }

    fun overlapping(ref: Ref, value: String, limit: Int = PAGE): List<Row> {
        verify(ref)
        val parsed = RuleInput.parse(value) ?: return emptyList()
        val interval = if (parsed.isIp) IpRange.ofRule(parsed.host) else null
        val boundedLimit = limit.coerceIn(1, PAGE)
        if (interval != null) {
            val found = linkedMapOf<Int, Row>()
            for (cidr in interval.cidrs()) {
                val part = requireNotNull(IpRange.ofRule(cidr)); val prefix = cidr.substringAfterLast('/').toInt()
                val keys = (0..prefix).map { p ->
                    val key = part.first.shiftRight(part.bits - p).shiftLeft(part.bits - p).toString(16).padStart(32, '0')
                    "${part.bits}:$p:$key"
                }
                val sql = "SELECT $COLUMNS FROM rules WHERE collection=? AND enabled=1 AND ordinal IN (" +
                    "SELECT ordinal FROM ip_segments WHERE collection=? AND prefix_key IN (${keys.joinToString(",") { "?" }}) UNION " +
                    "SELECT ordinal FROM ip_segments WHERE collection=? AND bits=? AND start BETWEEN ? AND ?) ORDER BY ordinal LIMIT ?"
                val args = listOf(ref.id, ref.id) + keys + listOf(ref.id, part.bits.toString(), part.startKey, part.endKey, boundedLimit.toString())
                db.rawQuery(sql, args.toTypedArray()).use { c -> while (c.moveToNext()) { val row = c.row(); found[row.ordinal] = row } }
                if (found.size >= boundedLimit) break
            }
            return found.values.take(boundedLimit)
        }
        val labels = parsed.host.split('.')
        val ancestors = (1..maxOf(0, labels.size - 2)).map { labels.drop(it).joinToString(".") }
        val branches = mutableListOf("SELECT ordinal FROM rules WHERE collection=? AND anchor=? AND scope IS NOT NULL")
        val args = mutableListOf(ref.id, parsed.host)
        if (ancestors.isNotEmpty()) {
            branches += "SELECT ordinal FROM rules WHERE collection=? AND anchor IN (${ancestors.joinToString(",") { "?" }}) AND (scope='SUFFIX' OR (scope='SINGLE' AND depth=?))"
            args += ref.id; args += ancestors; args += (labels.size - 1).toString()
        }
        if (parsed.scope != RuleScope.EXACT) {
            val prefix = parsed.host.reversed() + "."
            branches += "SELECT ordinal FROM rules WHERE collection=? AND reverse_anchor BETWEEN ? AND ? AND scope IS NOT NULL" + if (parsed.scope == RuleScope.SINGLE) " AND depth=?" else ""
            args += listOf(ref.id, prefix, prefix + '\uffff')
            if (parsed.scope == RuleScope.SINGLE) args += (labels.size + 1).toString()
        }
        return db.rawQuery("SELECT $COLUMNS FROM rules WHERE collection=? AND enabled=1 AND ordinal IN (${branches.joinToString(" UNION ")}) ORDER BY ordinal LIMIT ?",
            (listOf(ref.id) + args + boundedLimit.toString()).toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.row()) } }
    }

    @Synchronized fun asset(key: String, populate: (Writer) -> Unit): List<String> {
        val existing = db.rawQuery("SELECT collection FROM asset_collections WHERE asset=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
        existing?.let { ref(it)?.let { r -> return strings(r) } }
        val result = writer().use { w -> populate(w); strings(w.finish()) } as StoredList<String>
        db.execSQL("INSERT OR REPLACE INTO asset_collections(asset,collection) VALUES(?,?)", arrayOf(key, result.ref.id))
        db.execSQL("DELETE FROM asset_collections WHERE instr(asset,?)=1 AND asset<>?", arrayOf(key.substringBeforeLast(':') + ":", key))
        return result
    }

    class StoredList<T> internal constructor(val store: RuleContentStore, val ref: Ref, private val convert: (Row) -> T) : AbstractList<T>(), RebuildableRuleList<T> {
        init { store.retain(ref.id, this); store.retain(ref.id, ref) }
        override val size: Int get() = ref.size
        override fun get(index: Int): T { if (index !in indices) throw IndexOutOfBoundsException(); return convert(store.query(ref, offset = index, limit = 1).rows.single()) }
        override fun iterator(): Iterator<T> = store.rows(ref).map(convert).iterator()
        override fun forHost(host: String): List<T> = store.forHost(ref, host).map(convert)
        override fun rebuild(values: Sequence<T>): List<T> = store.writer(ref.kind).use { w ->
            values.forEach { value ->
                w.add(when (value) {
                    is String -> Row(value)
                    is RuleEntry -> Row(value.value, value.enabled, value.addedAt, value.disabledAt)
                    else -> error("Unsupported rule collection element")
                })
            }
            StoredList(store, w.finish(), convert)
        }
        override fun hashCode(): Int = ref.hash
        override fun equals(other: Any?): Boolean = when {
            other is StoredList<*> && ref == other.ref -> true
            other is StoredList<*> && (size != other.size || ref.hash != other.ref.hash) -> false
            else -> super.equals(other)
        }
        override fun toString(): String = "RuleCollection(${ref.id}, count=$size)"
    }
    class StoredMap internal constructor(val store: RuleContentStore, val ref: Ref) : AbstractMap<String, RuleAction>(), RebuildableRuleMap {
        init { store.retain(ref.id, this); store.retain(ref.id, ref) }
        override val size: Int get() = ref.size
        override fun get(key: String): RuleAction? = store.exact(ref, key)?.action
        override fun forHost(host: String): Map<String, RuleAction> = store.forHost(ref, host).associate { it.value to requireNotNull(it.action) }
        override fun rebuild(values: Sequence<Pair<String, RuleAction>>): Map<String, RuleAction> = store.persistOverrides(values)
        override val entries: Set<Map.Entry<String, RuleAction>> get() = object : AbstractSet<Map.Entry<String, RuleAction>>() {
            override val size: Int get() = ref.size
            override fun iterator(): Iterator<Map.Entry<String, RuleAction>> = store.rows(ref).map {
                java.util.AbstractMap.SimpleImmutableEntry(it.value, requireNotNull(it.action)) as Map.Entry<String, RuleAction>
            }.iterator()
        }
        // Enum.hashCode is process-specific; never reuse a previous process's map hash.
        private val currentHash by lazy { entries.sumOf { it.key.hashCode() xor it.value.hashCode() } }
        override fun hashCode(): Int = currentHash
        override fun equals(other: Any?): Boolean = if (other is StoredMap && ref == other.ref) true else super.equals(other)
        override fun toString(): String = "RuleOverrides(${ref.id}, count=$size)"
    }

    /** Conservative GC: keep settings/undo/tasks/assets and every live immutable reader. */
    fun collectGarbage(roots: Set<String>, activeJobs: Set<String>, olderThan: Long): Pair<Int, Int> {
        val keep = roots.toMutableSet()
        readers.removeIf { it.second.get() == null }
        readers.forEach { if (it.second.get() != null) keep += it.first }
        db.rawQuery("SELECT collection FROM asset_collections", null).use { c -> while (c.moveToNext()) keep += c.getString(0) }
        val keepJobs = activeJobs.toMutableSet()
        db.rawQuery("SELECT id FROM import_jobs ORDER BY updated DESC LIMIT 20", null).use { c -> while (c.moveToNext()) keepJobs += c.getString(0) }
        val expiredJobs = mutableListOf<String>()
        db.rawQuery("SELECT id,updated,json FROM import_jobs", null).use { c -> while (c.moveToNext()) {
            val id = c.getString(0)
            if (id !in keepJobs && c.getLong(1) < olderThan) expiredJobs += id
            else keep += referencesIn(c.getString(2))
        } }
        db.transaction {
            expiredJobs.forEach { id -> db.delete("import_issues", "job=?", arrayOf(id)); db.delete("import_jobs", "id=?", arrayOf(id)) }
        }
        val expired = mutableListOf<String>()
        db.rawQuery("SELECT id FROM collections WHERE created<? ORDER BY created", arrayOf(olderThan.toString())).use { c ->
            while (c.moveToNext() && expired.size < 8) { val id = c.getString(0); if (id !in keep) expired += id }
        }
        var deleted = 0
        expired.forEach { id -> synchronized(retentionLock) {
            if (readers.none { it.first == id && it.second.get() != null }) {
                deleted += db.delete("collections", "id=?", arrayOf(id)); countCache.remove(id)
            }
        } }
        return deleted to expiredJobs.size
    }

    companion object {
        const val STRINGS = 0
        const val ENTRIES = 1
        const val OVERRIDES = 2
        const val PAGE = 50
        const val BATCH = 250
        const val INLINE_LIMIT = 50
        private const val COLUMNS = "value,enabled,added,disabled,action,ordinal,collection"
        private val stores = mutableMapOf<String, RuleContentStore>()
        @Synchronized fun get(context: Context): RuleContentStore = stores.getOrPut(context.noBackupFilesDir.absolutePath) { RuleContentStore(context) }
        private fun Cursor.row() = Row(getString(0), getInt(1) != 0, getLong(2), getLong(3),
            if (isNull(4)) null else RuleAction.valueOf(getString(4)), getInt(5), getString(6), if (columnCount > 7) getString(7) else "")
        fun reference(value: Any?): Ref? = when (value) { is StoredList<*> -> value.ref; is StoredMap -> value.ref; else -> null }
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun referencesIn(value: String): Set<String> = UUID_PATTERN.findAll(value).map { it.value }.toSet()
    }
}
