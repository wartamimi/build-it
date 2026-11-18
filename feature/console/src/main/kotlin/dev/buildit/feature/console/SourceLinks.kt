package dev.buildit.feature.console

import java.io.File

/** A stack frame such as `at com.example.app.MainActivity.onCreate(MainActivity.java:23)`. */
data class Frame(val className: String, val method: String, val fileName: String, val line: Int, val range: IntRange)

object SourceLinks {
    private val frame = Regex("at ([\\w$.]+)\\.([\\w$<>]+)\\(([\\w$]+\\.(?:java|kt)):(\\d+)\\)")

    fun frames(text: String): List<Frame> = frame.findAll(text).map { m ->
        Frame(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4].toInt(), m.range)
    }.toList()

    /** The project file a frame points to, if the project has it (sources under app/src/main/java or kotlin). */
    fun resolve(projectDir: File, f: Frame): String? {
        val pkg = f.className.substringBefore('$').substringBeforeLast('.', "")
        val rel = (if (pkg.isEmpty()) "" else pkg.replace('.', '/') + "/") + f.fileName
        return listOf("app/src/main/java", "app/src/main/kotlin").map { "$it/$rel" }.firstOrNull { File(projectDir, it).isFile }
    }
}
