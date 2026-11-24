package com.example.data.local

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "emotepack_prefs")

enum class AppLanguage(val code: String) {
    SYSTEM("system"),
    SPANISH("es"),
    ENGLISH("en"),
    PORTUGUESE("pt"),
    RUSSIAN("ru"),
    FRENCH("fr"),
    GERMAN("de"),
    HINDI("hi"),
    INDONESIAN("in");

    companion object {
        fun fromCode(code: String?): AppLanguage = when (code) {
            "id" -> INDONESIAN
            else -> entries.firstOrNull { it.code == code } ?: SYSTEM
        }
    }
}

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("app_language")
    }

    val languageFlow: Flow<AppLanguage> = context.dataStore.data.map { prefs ->
        AppLanguage.fromCode(prefs[Keys.LANGUAGE])
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = language.code
        }
        applyLanguage(language)
    }

    suspend fun applySavedLanguageOnStartup() {
        val saved = languageFlow.first()
        if (saved != AppLanguage.SYSTEM) {
            applyLanguage(saved)
        }
    }

    fun applyLanguage(language: AppLanguage) {
        val locales = when (language) {
            AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            else -> LocaleListCompat.forLanguageTags(language.code)
        }
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
