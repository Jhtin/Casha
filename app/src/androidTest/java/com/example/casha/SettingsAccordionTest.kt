package com.example.casha

import android.graphics.Bitmap
import androidx.compose.material3.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.*
import com.example.casha.ui.SettingsScreen
import com.example.casha.ui.theme.CashaTheme
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class SettingsAccordionTest {
 @get:Rule val compose=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val name="settings_accordion_${UUID.randomUUID()}"
 @After fun cleanup(){context.deleteSharedPreferences(name);context.deleteSharedPreferences("finance_$name")}
 @Test fun sectionsStartCollapsedOnlyOneOpensAndAccountIsAtBottom(){
  compose.setContent {CashaTheme(darkTheme=false){SettingsScreen(LoanStore(context,name),FinanceStore(context,name)){Button({}){Text("Account sign in or sign out")}}}}
  compose.onNodeWithText("Companion reactions").assertDoesNotExist()
  compose.onNodeWithText("Gemini API key").assertDoesNotExist()
  compose.onNodeWithText("Require device unlock").assertDoesNotExist()
  compose.onNodeWithText("Appearance").performClick()
  compose.onNodeWithText("Light").assertExists()
  compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Meet Petot"))
  compose.onNodeWithText("Meet Petot").performClick()
  compose.onNodeWithText("Companion reactions").assertExists()
  compose.onNodeWithText("Light").assertDoesNotExist()
  compose.onNodeWithTag("settings-list").performScrollToNode(hasText("View app tutorial"))
  compose.onNodeWithText("View app tutorial").performClick()
  compose.onNodeWithText("Dual Debt & Collectible Ledgers").assertIsDisplayed()
  compose.onNodeWithText("Next").performClick()
  compose.onNodeWithText("Create Loans & Estimate Interest").assertIsDisplayed()
  compose.onNodeWithText("Skip tutorial").performClick()
  compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Meet Petot"))
  compose.onNodeWithText("Meet Petot").performClick()
  compose.onNodeWithText("Companion reactions").assertDoesNotExist()
  compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Account sign in or sign out"))
  compose.onNodeWithText("Account sign in or sign out").assertIsDisplayed()
  val account=compose.onNodeWithText("Account sign in or sign out").fetchSemanticsNode().boundsInRoot
  val privacy=compose.onNodeWithText("Privacy & backup").fetchSemanticsNode().boundsInRoot
  assertTrue(account.top>privacy.bottom)
  val folder=File(context.filesDir,"phase_previews").apply {mkdirs()}
  compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync()
  InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {b->File(folder,"settings-accordion.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}}
 }
}
