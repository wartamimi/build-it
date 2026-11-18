package dev.buildit.engine.ai

/** One file the model wants written, in full. [complete] is false when the reply was cut off inside it. */
data class ProposedFile(val path: String, val text: String, val complete: Boolean)

/** A reply split into prose (shown as chat) and file blocks (shown as reviewable diffs). */
data class ParsedReply(val prose: String, val files: List<ProposedFile>)

object ProposalParser {
    private val open = Regex("^\\s*<<<FILE path=\"([^\"]+)\">>>\\s*$")
    private val end = Regex("^\\s*<<<END>>>\\s*$")

    fun parse(reply: String): ParsedReply {
        val prose = StringBuilder()
        val files = mutableListOf<ProposedFile>()
        var path: String? = null
        val body = StringBuilder()
        for (line in reply.split('\n')) {
            val p = path
            if (p == null) {
                val m = open.matchEntire(line)
                if (m != null) { path = m.groupValues[1].trim(); body.setLength(0); prose.append("[").append(path).append("]\n") }
                else prose.append(line).append('\n')
            } else if (end.matches(line)) {
                files += ProposedFile(p, body.toString(), complete = true)
                path = null
            } else body.append(line).append('\n')
        }
        path?.let { files += ProposedFile(it, body.toString(), complete = false) }
        // The last proposal of a path wins; earlier ones were drafts in the same reply.
        val unique = files.associateBy { it.path }.values.toList()
        return ParsedReply(prose.toString().trimEnd(), unique)
    }
}
