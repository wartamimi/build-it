package dev.buildit.core.model

enum class ThemeMode { System, Light, Dark }

enum class AppLanguage(val tag: String) {
    System(""),
    English("en"),
    Indonesian("in"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: System
    }
}

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val language: AppLanguage = AppLanguage.System,
    val editorFontSizeSp: Int = DEFAULT_EDITOR_FONT_SIZE,
    val autoSave: Boolean = true,
) {
    companion object {
        const val DEFAULT_EDITOR_FONT_SIZE = 14
        val EDITOR_FONT_SIZE_RANGE = 10..24
    }
}
