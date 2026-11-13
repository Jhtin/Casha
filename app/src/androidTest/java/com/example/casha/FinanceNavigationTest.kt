package com.example.casha

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.*
import com.example.casha.domain.*
import com.example.casha.ui.CashaApp
import com.example.casha.ui.theme.CashaTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class FinanceNavigationTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="finance_ui_${UUID.randomUUID()}"
    private val store get()=LoanStore(context,name)
    private val finance get()=FinanceStore(context,name)
    @After fun cleanUp() {context.deleteSharedPreferences(name);context.deleteSharedPreferences("finance_$name")}
    private fun picture(name:String) {
        val folder=File(context.filesDir,"phase_previews").apply {mkdirs()}
        compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(150)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {image->File(folder,name).outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}}
        if(name=="dashboard.png") {
            val icon=context.packageManager.getApplicationIcon(context.packageName)
            val bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
            icon.setBounds(0,0,192,192);icon.draw(android.graphics.Canvas(bitmap))
            File(folder,"launcher-logo.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun navigateBudgetLogReverseAndKeepAiManualWithoutAKey() {
        val today=LocalDate.now()
        store.save(listOf(Loan(id="sample-loan",party="Home Credit",mode=LoanMode.I_OWE,total=600_000,count=3,firstDue=today.plusDays(5),frequency=Frequency.MONTHLY)))
        finance.saveProfile(PaydayProfile(today,today.plusDays(15),1_500_000,600_000,100_000))
        compose.setContent {CashaTheme(darkTheme=false){CashaApp(store,name)}}
        compose.waitUntil(5000){compose.onAllNodesWithText("Home Credit").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("Home Credit").onFirst().assertExists();picture("dashboard.png")
        compose.onAllNodesWithText("Home Credit").onFirst().performClick()
        compose.onNodeWithTag("loan-details-list").assertIsDisplayed();picture("loan-details.png")
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("loan-list").performScrollToNode(hasTestTag("loan-search"))
        compose.onNodeWithTag("loan-search").performTextInput("no-matching-lender")
        compose.onNodeWithTag("loan-list").performScrollToNode(hasText("No matching loans"))
        compose.onNodeWithText("Clear search").performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(5000){compose.onAllNodesWithText("Payday").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Payday").performClick()
        compose.onNodeWithText("Payday strategist").assertIsDisplayed();picture("payday.png")
        compose.onNodeWithText("Log cash").performClick()
        compose.onNodeWithText("Amount (PHP)").performTextInput("10.00")
        compose.onNodeWithText("Note").performTextInput("Coffee")
        compose.onNodeWithText("Save cash entry").performClick()
        compose.runOnIdle {assertEquals(1000L,finance.cashEntries().single().amount)}
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Reverse cash entry"))
        compose.onNodeWithText("Reverse cash entry").performClick()
        compose.onNodeWithText("Confirm reversal").performClick()
        compose.runOnIdle {assertEquals(2,finance.cashEntries().size)}
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Budget details"))
        compose.onNodeWithText("Budget details").performClick()
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Save allocation plan"))
        compose.onNodeWithText("Save allocation plan").performClick()
        compose.runOnIdle {assertTrue(store.load().single().payments.isEmpty())}
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("How this plan works"))
        compose.onNodeWithText("How this plan works").performClick()
        compose.onNodeWithText("About your payday plan").assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithText("AI").performClick()
        compose.onNodeWithText("Petot").assertIsDisplayed();picture("assistant.png")
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
        compose.onNodeWithTag("chat-composer").performTextInput("May extra income akong 2500")
        compose.onNodeWithContentDescription("Ask Gemini").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Confirm the free-tier key",substring=true).fetchSemanticsNodes().isNotEmpty()}
        picture("assistant-error.png")
        compose.onNodeWithText("New chat").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Receipts").performClick()
        compose.onNodeWithText("Scan receipt screenshot").assertIsDisplayed();picture("receipts.png")
        compose.onNodeWithText("AI chat").performClick()
        compose.onNodeWithText("New chat").assertExists()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Reminders").assertExists();picture("settings.png")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Gemini assistant setup"))
        compose.onNodeWithText("Gemini assistant setup").assertExists()
        compose.onNodeWithText("Gemini assistant setup").performClick()
        compose.onNodeWithText("Save key").performScrollTo().assertIsNotEnabled()
    }
    @Test fun darkLargeTextChatKeepsComposerAndDraftAcrossTabs() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(3f,1.3f)) {
                CashaTheme(darkTheme=true){CashaApp(store,name)}
            }
        }
        compose.onNodeWithText("Payday").performClick()
        compose.onNodeWithText("Log cash").assertIsDisplayed();picture("payday-dark-large.png")
        compose.onNodeWithText("AI").performClick()
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
        compose.onNodeWithTag("chat-list").performScrollToNode(hasText("Log an expense"))
        compose.onNodeWithText("Log an expense").performClick()
        compose.onNodeWithText("Receipts").performClick()
        compose.onNodeWithText("AI chat").performClick()
        compose.onNodeWithTag("chat-composer").assertTextContains("I spent 350 pesos on groceries from E-Wallet")
        picture("assistant-dark-large.png")
        compose.onNodeWithTag("chat-list").performScrollToNode(hasText("Gemini API setup"))
        compose.onNodeWithText("Gemini API setup").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings-list").assertExists()
    }

}
