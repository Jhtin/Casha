package com.example.casha.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label:String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

object ThemePreference {
    private const val FILE="app_appearance"
    private const val KEY="theme"
    fun preferences(context:Context):SharedPreferences=context.applicationContext.getSharedPreferences(FILE,Context.MODE_PRIVATE)
    fun read(context:Context):ThemeMode=runCatching {ThemeMode.valueOf(preferences(context).getString(KEY,ThemeMode.SYSTEM.name)!!)}.getOrDefault(ThemeMode.SYSTEM)
    fun save(context:Context,mode:ThemeMode):Boolean=preferences(context).edit().putString(KEY,mode.name).commit()
}

@Composable fun rememberThemeMode():State<ThemeMode> {
    val context=LocalContext.current.applicationContext
    val state=remember(context){mutableStateOf(ThemePreference.read(context))}
    DisposableEffect(context) {
        val preferences=ThemePreference.preferences(context)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_->state.value=ThemePreference.read(context) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        state.value=ThemePreference.read(context)
        onDispose {preferences.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return state
}
