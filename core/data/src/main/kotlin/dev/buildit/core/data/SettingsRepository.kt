package dev.buildit.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.buildit.core.model.AppLanguage
import dev.buildit.core.model.ThemeMode
import dev.buildit.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setEditorFontSize(sp: Int)
    suspend fun setAutoSave(enabled: Boolean)
}

class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val language = stringPreferencesKey("language")
        val editorFont = intPreferencesKey("editor_font_size")
        val autoSave = booleanPreferencesKey("auto_save")
    }

    override val settings: Flow<UserSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val defaults = UserSettings()
            UserSettings(
                themeMode = p[Keys.theme]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: defaults.themeMode,
                dynamicColor = p[Keys.dynamic] ?: defaults.dynamicColor,
                language = p[Keys.language]?.let(AppLanguage::fromTag) ?: defaults.language,
                editorFontSizeSp = (p[Keys.editorFont] ?: defaults.editorFontSizeSp).coerceIn(UserSettings.EDITOR_FONT_SIZE_RANGE),
                autoSave = p[Keys.autoSave] ?: defaults.autoSave,
            )
        }

    override suspend fun setThemeMode(mode: ThemeMode) { store.edit { it[Keys.theme] = mode.name } }
    override suspend fun setDynamicColor(enabled: Boolean) { store.edit { it[Keys.dynamic] = enabled } }
    override suspend fun setLanguage(language: AppLanguage) { store.edit { it[Keys.language] = language.tag } }
    override suspend fun setEditorFontSize(sp: Int) {
        store.edit { it[Keys.editorFont] = sp.coerceIn(UserSettings.EDITOR_FONT_SIZE_RANGE) }
    }
    override suspend fun setAutoSave(enabled: Boolean) { store.edit { it[Keys.autoSave] = enabled } }
}
