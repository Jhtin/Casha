package com.example.casha

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.LoanStore
import com.example.casha.ui.CashaApp
import com.example.casha.ui.theme.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class ThemePreferenceTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="theme_${UUID.randomUUID()}"
    private lateinit var original:ThemeMode
    @Before fun setup(){original=ThemePreference.read(context);ThemePreference.save(context,ThemeMode.SYSTEM)}
    @After fun cleanup(){ThemePreference.save(context,original);context.deleteSharedPreferences(name);context.deleteSharedPreferences("finance_$name")}
    @Test fun settingsUpdatesAppImmediatelyAndPersistsAllThreeChoices() {
        var dark=false
        var systemDark=false
        compose.setContent {
            CashaTheme {
                val surfaceIsDark=MaterialTheme.colorScheme.background.luminance()<0.5f
                val deviceIsDark=isSystemInDarkTheme()
                SideEffect {dark=surfaceIsDark;systemDark=deviceIsDark}
                CashaApp(LoanStore(context,name),name)
            }
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Appearance"))
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Dark").assertIsSelected()
        compose.runOnIdle {assertTrue(dark);assertEquals(ThemeMode.DARK,ThemePreference.read(context))}
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithText("Light").assertIsSelected()
        compose.runOnIdle {assertFalse(dark);assertEquals(ThemeMode.LIGHT,ThemePreference.read(context))}
        compose.onNodeWithText("System").performClick()
        compose.onNodeWithText("System").assertIsSelected()
        compose.runOnIdle {assertEquals(systemDark,dark);assertEquals(ThemeMode.SYSTEM,ThemePreference.read(context))}
    }
}
