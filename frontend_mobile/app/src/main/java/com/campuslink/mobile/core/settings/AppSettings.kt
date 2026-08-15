package com.campuslink.mobile.core.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLanguage { ENGLISH, CHINESE }

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val mutableLanguage = MutableStateFlow(
        runCatching { AppLanguage.valueOf(preferences.getString("language", "ENGLISH")!!) }
            .getOrDefault(AppLanguage.ENGLISH),
    )
    private val mutableDark = MutableStateFlow(preferences.getBoolean("dark", false))
    val language: StateFlow<AppLanguage> = mutableLanguage.asStateFlow()
    val dark: StateFlow<Boolean> = mutableDark.asStateFlow()

    fun setLanguage(value: AppLanguage) {
        preferences.edit().putString("language", value.name).apply()
        mutableLanguage.value = value
    }

    fun setDark(value: Boolean) {
        preferences.edit().putBoolean("dark", value).apply()
        mutableDark.value = value
    }
}
