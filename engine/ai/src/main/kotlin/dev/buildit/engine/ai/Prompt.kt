package dev.buildit.engine.ai

import dev.buildit.engine.project.AppConfig

/** A project file sent to the model, with its content at the time it was sent. */
data class ContextFile(val path: String, val text: String)

/** Builds the instructions and the user turn for the assistant. Pure, so it is unit-tested. */
object Prompts {
    const val FILE_OPEN = "<<<FILE path=\""
    const val FILE_END = "<<<END>>>"

    fun system(projectName: String, app: AppConfig): String = """
        |You are the coding assistant inside Build It, an Android IDE running on the user's phone.
        |
        |Project "$projectName": application id ${app.packageName}, namespace ${app.effectiveNamespace}, minSdk ${app.minSdk}, targetSdk ${app.targetSdk}, compileSdk ${app.compileSdk}, language ${app.language}.
        |Sources live in app/src/main/java, resources in app/src/main/res, the manifest is app/src/main/AndroidManifest.xml.
        |The on-device builder compiles Java with the Android platform only: no Kotlin sources and no library dependencies (no AndroidX, no Material Components). Suggest code that builds under those limits unless the user asks otherwise, and say so when something needs a library.
        |
        |When you change or create files, write each one in full, with nothing else on the marker lines:
        |$FILE_OPEN<path relative to the project root>">>>
        |<the complete new content of the file>
        |$FILE_END
        |Do not wrap these blocks in Markdown code fences. Use them only for files you want written; for snippets or examples use ordinary code fences. The user reviews every change as a diff and applies it themselves.
        |Keep explanations short and put them before the files. Do not invent APIs; if you are unsure something exists on the project's API levels, say so.
        """.trimMargin()

    /**
     * The text of one user turn: the question, then the selected files and the last build's
     * problems, so the model sees the exact content the user's change is based on.
     */
    fun userTurn(question: String, files: List<ContextFile>, buildProblems: List<String>): String = buildString {
        append(question.trim())
        if (files.isNotEmpty()) {
            append("\n\nProject files:\n")
            files.forEach { f ->
                append("\n<file path=\"").append(f.path).append("\">\n").append(f.text)
                if (!f.text.endsWith("\n")) append('\n')
                append("</file>\n")
            }
        }
        if (buildProblems.isNotEmpty()) {
            append("\nProblems from the last build:\n")
            buildProblems.forEach { append("- ").append(it).append('\n') }
        }
    }
}
