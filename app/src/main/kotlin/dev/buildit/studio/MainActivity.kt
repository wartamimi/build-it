package dev.buildit.studio

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.theme.BuildItTheme
import dev.buildit.core.model.AppLanguage
import dev.buildit.studio.navigation.BuildItApp

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as BuildItApplication).container
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings
            LaunchedEffect(s?.language) { s?.language?.let(::applyLanguage) }
            if (s != null) {
                BuildItTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                    BuildItApp(container)
                }
            }
        }
    }

    private fun applyLanguage(language: AppLanguage) {
        val wanted = if (language == AppLanguage.System) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language.tag)
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != wanted.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(wanted)
        }
    }
}
