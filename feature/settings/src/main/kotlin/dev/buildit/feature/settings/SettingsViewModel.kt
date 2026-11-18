package dev.buildit.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.buildit.core.data.ProjectRepository
import dev.buildit.core.data.SettingsRepository
import dev.buildit.core.model.AppLanguage
import dev.buildit.core.model.ThemeMode
import dev.buildit.core.model.UserSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SettingsMessage {
    data class Reindexed(val failed: Int) : SettingsMessage
    data class Failed(val detail: String) : SettingsMessage
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val projects: ProjectRepository,
) : ViewModel() {
    val settings: StateFlow<UserSettings?> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    fun setLanguage(language: AppLanguage) = viewModelScope.launch { settingsRepository.setLanguage(language) }
    fun setEditorFontSize(sp: Int) = viewModelScope.launch { settingsRepository.setEditorFontSize(sp) }
    fun setAutoSave(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAutoSave(enabled) }

    fun reindex() = viewModelScope.launch {
        try {
            _messages.send(SettingsMessage.Reindexed(projects.reindex()))
        } catch (e: Exception) {
            _messages.send(SettingsMessage.Failed(e.message ?: e.javaClass.simpleName))
        }
    }
}
