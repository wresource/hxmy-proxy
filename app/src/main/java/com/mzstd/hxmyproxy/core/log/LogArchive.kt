package com.mzstd.hxmyproxy.core.log

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.PriorityQueue
import java.util.TimeZone

/** A frozen on-disk view, searched before selecting a bounded page. No all-entry UI list. */
class LogArchive private constructor(private val parts: List<Part>, val capturedAt: Long,
    private val timeZone: TimeZone, private val ownedDirectory: File?) : AutoCloseable {
    data class Part(val file: File, val key: Boolean, val sectionMarkers: Boolean = false)
    data class Cursor(val at: Long, val order: Long) : Comparable<Cursor> {
        override fun compareTo(other: Cursor): Int = at.compareTo(other.at).takeIf { it != 0 } ?: order.compareTo(other.order)
    }
    data class Entry(val cursor: Cursor, val part: Int, val start: Long, val end: Long,
        val timestamp: String, val level: String, val tag: String, val message: String,
        val hasDetails: Boolean, val characters: Int, val crash: Boolean, val archiveId: String) {
        val id: String get() = "$archiveId:$part:$start:$end"
    }
    data class Page(val entries: List<Entry>, val total: Int, val errors: Int, val warnings: Int,
        val hasMore: Boolean, val next: Cursor?, val all: Int)
    data class TextPage(val text: String, val hasMore: Boolean)
    private val archiveId = java.util.UUID.randomUUID().toString()

    /** A key/full mirror is removed by occurrence, not by stack fingerprint or global distinct(). */
    fun query(level: String = "ALL", search: String = "", crashesOnly: Boolean = false,
        before: Cursor? = null, limit: Int = 50, checkCancelled: () -> Unit = {}): Page {
        require(limit in 1..50)
        val queue = PriorityQueue<Entry>(compareBy { it.cursor })
        val keyCopies = HashMap<String, Int>()
        val fullCopies = HashMap<String, Int>()
        val digest = MessageDigest.getInstance("SHA-256")
        val calendar = Calendar.getInstance(timeZone, Locale.US).apply { timeInMillis = capturedAt; isLenient = false }
        val year = calendar.get(Calendar.YEAR)
        var order = 0L; var total = 0; var eligible = 0; var errors = 0; var warnings = 0
        val needle = search.trim().lowercase(Locale.ROOT)
        parts.forEachIndexed { index, part ->
            part.file.inputStream().use { input ->
                val reader = ByteLines(input)
                var key = part.key
                var head: MatchResult? = null
                var start = 0L; var count = 0; var details = false; var matches = false
                fun finish(end: Long) {
                    val h = head ?: return
                    checkCancelled()
                    val bytes = digest.digest()
                    val hex = CharArray(bytes.size * 2)
                    bytes.forEachIndexed { i, b -> val n = b.toInt() and 255; hex[i * 2] = HEX[n ushr 4]; hex[i * 2 + 1] = HEX[n and 15] }
                    val identity = String(hex)
                    val duplicate = if (key) {
                        keyCopies[identity] = (keyCopies[identity] ?: 0) + 1; false
                    } else {
                        val mirrored = keyCopies[identity] ?: 0
                        if (mirrored == 0) false else {
                            val occurrence = (fullCopies[identity] ?: 0) + 1
                            fullCopies[identity] = occurrence
                            occurrence <= mirrored
                        }
                    }
                    head = null
                    if (duplicate) return
                    val (timestamp, severity, tag, message) = h.destructured
                    if (severity == "E") errors++
                    if (severity == "W") warnings++
                    val crash = tag.equals("crash", true) || tag == "AndroidRuntime" ||
                        (message.contains("evt=proc.lastExit") && (message.contains("reason=CRASH") || message.contains("reason=ANR") || message.contains("reason=INITIALIZATION_FAILURE")))
                    val at = if (message.contains("evt=proc.lastExit")) EXIT_AT.find(message)?.groupValues?.get(1)?.toLongOrNull()
                        ?: eventTimestamp(timestamp, calendar, year) else eventTimestamp(timestamp, calendar, year)
                    val cursor = Cursor(at, order++)
                    val accepted = when (level) { "ERROR" -> severity == "E"; "WARN" -> severity == "E" || severity == "W"; else -> true }
                    if (!accepted || !matches || crashesOnly && !crash) return
                    total++
                    if (before != null && cursor >= before) return
                    eligible++
                    val entry = Entry(cursor, index, start, end, timestamp, severity, tag, message.take(600), details || message.length > 600, count, crash, archiveId)
                    if (queue.size < limit) queue += entry
                    else if (entry.cursor > requireNotNull(queue.peek()).cursor) { queue.poll(); queue += entry }
                }
                while (true) {
                    checkCancelled()
                    val line = reader.next() ?: break
                    if (part.sectionMarkers && (line.text == "===== KEY EVENTS (independent rolling buffer) =====" || line.text == "===== FULL LOG =====")) {
                        finish(line.start); key = line.text.contains("KEY EVENTS"); continue
                    }
                    val found = HEAD.matchEntire(line.text)
                    if (found != null) {
                        finish(line.start)
                        head = found; start = line.start; details = false; count = line.characters
                        matches = needle.isEmpty() || line.text.lowercase(Locale.ROOT).contains(needle)
                        digest.reset(); digest.update(line.text.toByteArray(Charsets.UTF_8)); digest.update(10.toByte())
                    } else if (head != null) {
                        count += line.characters
                        if (line.text.isNotEmpty()) {
                            details = true
                            if (!matches && line.text.lowercase(Locale.ROOT).contains(needle)) matches = true
                            digest.update(line.text.toByteArray(Charsets.UTF_8)); digest.update(10.toByte())
                        }
                    }
                }
                finish(reader.position)
            }
        }
        val entries = queue.toList().sortedByDescending { it.cursor }
        return Page(entries, total, errors, warnings, eligible > entries.size, entries.lastOrNull()?.cursor, order.toInt())
    }

    /** Read only the selected record, in bounded text pages; even a huge stack cannot become one Text node. */
    fun text(entry: Entry, offset: Int = 0, limit: Int = 8_192): TextPage {
        require(offset >= 0 && limit in 1..32_768)
        return recordStream(entry).use { input -> InputStreamReader(input, Charsets.UTF_8).use readerUse@ { reader ->
            var remaining = offset.toLong()
            while (remaining > 0) { val n = reader.skip(remaining); if (n <= 0) return@readerUse TextPage("", false); remaining -= n }
            val buffer = CharArray(limit + 1); var read = 0
            while (read < buffer.size) { val n = reader.read(buffer, read, buffer.size - read); if (n < 0) break; read += n }
            var length = minOf(limit, read)
            if (read > length && length > 0 && buffer[length - 1].isHighSurrogate() && buffer[length].isLowSurrogate()) length--
            TextPage(String(buffer, 0, length), read > length)
        } }
    }
    fun export(entry: Entry, output: java.io.OutputStream) { recordStream(entry).use { it.copyTo(output, 64 * 1024) } }
    private fun recordStream(entry: Entry): InputStream {
        check(entry.archiveId == archiveId) { "The log view has changed. Open the entry again." }
        val file = RandomAccessFile(parts[entry.part].file, "r"); file.seek(entry.start)
        var left = entry.end - entry.start
        return object : InputStream() {
            override fun read(): Int { if (left <= 0) return -1; val b = file.read(); if (b >= 0) left--; return b }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = file.read(b, off, minOf(left, len.toLong()).toInt()); if (n > 0) left -= n; return n
            }
            override fun close() = file.close()
        }
    }
    private fun eventTimestamp(value: String, calendar: Calendar, year: Int): Long = runCatching {
        calendar.clear(); calendar.set(year, value.substring(0, 2).toInt() - 1, value.substring(3, 5).toInt(),
            value.substring(6, 8).toInt(), value.substring(9, 11).toInt(), value.substring(12, 14).toInt())
        calendar.set(Calendar.MILLISECOND, value.substring(15, 18).toInt())
        var at = calendar.timeInMillis
        if (at > capturedAt + 86_400_000L) { calendar.set(Calendar.YEAR, year - 1); at = calendar.timeInMillis }
        at
    }.getOrDefault(Long.MIN_VALUE)
    override fun close() { ownedDirectory?.deleteRecursively() }

    private data class Line(val text: String, val start: Long, val characters: Int)
    private class ByteLines(private val input: InputStream) {
        private val buffer = ByteArray(64 * 1024)
        private var size = 0; private var index = 0; private var base = 0L
        val position get() = base + index
        fun next(): Line? {
            val start = position; var pieces: ByteArrayOutputStream? = null
            while (true) {
                if (index == size) {
                    base += size; index = 0; size = input.read(buffer).coerceAtLeast(0)
                    if (size == 0) return pieces?.let { val raw = it.toString(Charsets.UTF_8.name()); Line(raw.removeSuffix("\r"), start, raw.length) }
                }
                var end = index
                while (end < size && buffer[end] != 10.toByte()) end++
                if (end < size) {
                    val raw = if (pieces == null) String(buffer, index, end - index, Charsets.UTF_8)
                        else { pieces.write(buffer, index, end - index); pieces.toString(Charsets.UTF_8.name()) }
                    index = end + 1
                    return Line(raw.removeSuffix("\r"), start, raw.length + 1)
                }
                if (pieces == null) pieces = ByteArrayOutputStream()
                pieces.write(buffer, index, size - index); index = size
            }
        }
    }

    companion object {
        private val HEAD = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([IWE])/([^:]+): (.*)$""")
        private val EXIT_AT = Regex("""\bat=(\d+)""")
        private const val HEX = "0123456789abcdef"
        fun capture(directory: File, checkCancelled: () -> Unit = {}): LogArchive {
            val snapshot = FileLog.snapshotTo(directory, checkCancelled)
            return LogArchive(snapshot.parts.map { Part(it.file, it.key) }, snapshot.capturedAt, TimeZone.getDefault(), directory)
        }
        fun fromExport(file: File, capturedAt: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): LogArchive =
            LogArchive(listOf(Part(file, false, true)), capturedAt, zone, null)
    }
}
