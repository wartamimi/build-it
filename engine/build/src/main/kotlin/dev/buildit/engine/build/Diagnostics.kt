package dev.buildit.engine.build

/** A problem found while building, anchored to a project-relative file and 1-based line (0 = unknown). */
data class BuildDiagnostic(
    val severity: Severity,
    val message: String,
    val file: String? = null,
    val line: Int = 0,
) {
    enum class Severity { Error, Warning }

    override fun toString() = buildString {
        if (file != null) { append(file); if (line > 0) append(':').append(line); append(": ") }
        append(severity.name.lowercase()).append(": ").append(message)
    }
}

class Diagnostics {
    private val items = mutableListOf<BuildDiagnostic>()
    val all: List<BuildDiagnostic> get() = items.toList()
    val hasErrors: Boolean get() = items.any { it.severity == BuildDiagnostic.Severity.Error }

    fun error(message: String, file: String? = null, line: Int = 0) { items += BuildDiagnostic(BuildDiagnostic.Severity.Error, message, file, line) }
    fun warning(message: String, file: String? = null, line: Int = 0) { items += BuildDiagnostic(BuildDiagnostic.Severity.Warning, message, file, line) }
    fun addAll(d: Collection<BuildDiagnostic>) { items += d }
}
