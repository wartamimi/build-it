package dev.buildit.engine.project

/** A single validation problem, with a stable [code] that the UI maps to a localized message. */
data class ValidationIssue(val field: String, val code: String, val detail: String = "")

object ProjectValidation {
    const val MAX_NAME_LENGTH = 50
    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "_",
    )
    private val SEGMENT = Regex("[a-zA-Z][a-zA-Z0-9_]*")
    private val FORBIDDEN_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    fun validateName(name: String): List<ValidationIssue> {
        val trimmed = name.trim()
        return buildList {
            if (trimmed.isEmpty()) add(ValidationIssue("name", "empty"))
            if (trimmed.length > MAX_NAME_LENGTH) add(ValidationIssue("name", "too_long", "$MAX_NAME_LENGTH"))
            if (FORBIDDEN_NAME_CHARS.containsMatchIn(trimmed)) add(ValidationIssue("name", "invalid_chars"))
        }
    }

    /** Android application IDs: at least two segments, each a Java identifier starting with a letter. */
    fun validatePackageName(pkg: String): List<ValidationIssue> {
        if (pkg.isBlank()) return listOf(ValidationIssue("packageName", "empty"))
        val segments = pkg.split('.')
        return buildList {
            if (segments.size < 2) add(ValidationIssue("packageName", "too_few_segments"))
            segments.forEach { s ->
                when {
                    s.isEmpty() -> add(ValidationIssue("packageName", "empty_segment"))
                    !SEGMENT.matches(s) -> add(ValidationIssue("packageName", "invalid_segment", s))
                    s in JAVA_KEYWORDS -> add(ValidationIssue("packageName", "keyword", s))
                }
            }
        }.distinct()
    }

    fun validateSdk(app: AppConfig): List<ValidationIssue> = buildList {
        if (app.minSdk < 21) add(ValidationIssue("minSdk", "below_supported", "21"))
        if (app.minSdk > app.targetSdk) add(ValidationIssue("minSdk", "above_target"))
        if (app.targetSdk > app.compileSdk) add(ValidationIssue("targetSdk", "above_compile"))
        if (app.versionCode < 1) add(ValidationIssue("versionCode", "not_positive"))
        if (app.versionName.isBlank()) add(ValidationIssue("versionName", "empty"))
    }

    fun validate(manifest: ProjectManifest): List<ValidationIssue> =
        validateName(manifest.name) + validatePackageName(manifest.app.packageName) + validateSdk(manifest.app)

    /** Suggests a package name segment from a display name, e.g. "My Cool App" -> "mycoolapp". */
    fun suggestPackageSegment(name: String): String {
        val cleaned = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.dropWhile { it.isDigit() }
        return when {
            cleaned.isEmpty() -> "app"
            cleaned in JAVA_KEYWORDS -> "${cleaned}app"
            else -> cleaned
        }
    }
}
