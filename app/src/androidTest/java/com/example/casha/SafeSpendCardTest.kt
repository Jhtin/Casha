package com.example.casha

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.casha.ui.*
import com.example.casha.ui.theme.CashaTheme
import org.junit.Rule
import org.junit.Test

class SafeSpendCardTest {
    @get:Rule val compose=createComposeRule()
    @Test fun cardShowsExactBandsAndNeutralWhenBudgetUnavailable() {
        var safe by mutableStateOf(5001L)
        var active by mutableStateOf(true)
        compose.setContent {CashaTheme(darkTheme=false){Surface(Modifier.fillMaxSize()){Box(Modifier.safeDrawingPadding()){SafeSpendCard(safe,500,10000,active,"Next payday Nov 16")}}}}
        compose.onNodeWithContentDescription("Safe-to-spend status: green").assertIsDisplayed()
        compose.runOnIdle {safe=5000}
        compose.onNodeWithContentDescription("Safe-to-spend status: yellow").assertIsDisplayed()
        compose.runOnIdle {safe=2000}
        compose.onNodeWithContentDescription("Safe-to-spend status: yellow").assertIsDisplayed()
        compose.runOnIdle {safe=1999}
        compose.onNodeWithContentDescription("Safe-to-spend status: red").assertIsDisplayed()
        compose.runOnIdle {active=false}
        compose.onNodeWithContentDescription("Safe-to-spend status: unset").assertIsDisplayed()
    }
}
