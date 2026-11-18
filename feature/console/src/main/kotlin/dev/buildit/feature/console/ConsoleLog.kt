package dev.buildit.feature.console

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConsoleLevel(val letter: Char) {
    Verbose('V'), Debug('D'), Info('I'), Warn('W'), Error('E'), Assert('F'),
    /** An uncaught exception that ended the process. */
    Crash('!'),
    /** Activity lifecycle and process start, from the bridge. */
    Event('*');

    companion object {
        fun of(c: Char) = when (c) { 'V' -> Verbose; 'D' -> Debug; 'I' -> Info; 'W' -> Warn; 'E' -> Error; 'F', 'A' -> Assert; else -> Info }
    }
}

data class ConsoleEntry(
    val id: Long,
    val packageName: String,
    val session: Long,
    val time: Long,
    val level: ConsoleLevel,
    val tag: String,
    val text: String,
    val pid: Int,
)

/** What a debug build sends, before parsing. */
data class RawEntry(val packageName: String, val session: Long, val pid: Int, val time: Long, val kind: String, val text: String, val thread: String?)

/**
 * Entries received from debug builds, kept in memory per app (newest [CAPACITY]). They are not
 * written to disk: a console is for the run in front of you.
 */
class ConsoleLog {
    private val _entries = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val entries: StateFlow<List<ConsoleEntry>> = _entries.asStateFlow()
    private var nextId = 1L

    fun add(raw: List<RawEntry>) {
        if (raw.isEmpty()) return
        _entries.update { current ->
            val parsed = synchronized(this) { raw.map { parse(it, nextId++) } }
            val all = current + parsed
            if (all.size > CAPACITY) all.drop(all.size - CAPACITY) else all
        }
    }

    fun clear(packageName: String) = _entries.update { l -> l.filterNot { it.packageName == packageName } }

    companion object {
        const val CAPACITY = 5_000

        /** Shared by the provider (which the system creates) and the screens. */
        val shared = ConsoleLog()

        /** Decides whether a calling package may write; set by the app at startup. Denies by default. */
        @Volatile var verifier: (packageName: String) -> Boolean = { false }

        // threadtime: "11-18 12:01:02.345  1234  1250 E AndroidRuntime: message"
        private val threadtime = Regex("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*: (.*)$")

        fun parse(r: RawEntry, id: Long): ConsoleEntry = when (r.kind) {
            "log" -> threadtime.matchEntire(r.text)?.let { m ->
                ConsoleEntry(id, r.packageName, r.session, r.time, ConsoleLevel.of(m.groupValues[4][0]), m.groupValues[5], m.groupValues[6], m.groupValues[2].toInt())
            } ?: ConsoleEntry(id, r.packageName, r.session, r.time, ConsoleLevel.Info, "", r.text, r.pid)
            "crash" -> ConsoleEntry(id, r.packageName, r.session, r.time, ConsoleLevel.Crash, r.thread ?: "", r.text.trimEnd(), r.pid)
            else -> ConsoleEntry(id, r.packageName, r.session, r.time, ConsoleLevel.Event, r.kind, r.text, r.pid)
        }
    }
}
