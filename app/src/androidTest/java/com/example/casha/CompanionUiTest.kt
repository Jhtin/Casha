package com.example.casha

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.*
import com.example.casha.domain.*
import com.example.casha.ui.*
import com.example.casha.ui.theme.CashaTheme
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID
import java.io.File

class CompanionUiTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="companion_${UUID.randomUUID()}"
    private val finance by lazy {FinanceStore(context,name)}
    private val store by lazy {LoanStore(context,name)}
    private var previous=true to true
    @Before fun setup(){previous=CompanionPreference.read(context);CompanionPreference.save(context,"reactions",true);CompanionPreference.save(context,"motion",false)}
    @After fun cleanup(){CompanionPreference.save(context,"reactions",previous.first);CompanionPreference.save(context,"motion",previous.second);context.deleteSharedPreferences(name);context.deleteSharedPreferences("finance_$name")}
    @Test fun spendingWarningShowsAfterSaveAndCanBeDisabled() {
        val today=LocalDate.now()
        finance.saveProfile(PaydayProfile(today,today.plusDays(10),10000,0))
        compose.setContent {CashaTheme(darkTheme=false){CashaApp(store,name)}}
        compose.onNodeWithText("Expense").performClick()
        compose.onNodeWithText("Amount (PHP)").performTextInput("25")
        compose.onNodeWithText("Save expense").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("petot-popup").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Tiny pause, big care").assertIsDisplayed()
        val folder=File(context.filesDir,"phase_previews").apply {mkdirs()}
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->File(folder,"petot-spending.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        compose.onNodeWithContentDescription("Dismiss Petot message").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Meet Petot"))
        compose.onNodeWithText("Meet Petot").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Companion reactions"))
        compose.onNodeWithText("Companion reactions").performClick()
        compose.runOnIdle {assertFalse(CompanionPreference.read(context).first)}
        compose.onNodeWithText("Expense").performClick()
        compose.onNodeWithText("Amount (PHP)").performTextInput("25")
        compose.onNodeWithText("Save expense").performClick()
        compose.waitUntil(5000){finance.cashEntries().size==2}
        compose.onNodeWithTag("petot-popup").assertDoesNotExist()
    }
    @Test fun finalPaymentShowsCelebrationWithoutClosingLoanDetails() {
        val today=LocalDate.now()
        finance.saveProfile(PaydayProfile(today,today.plusDays(10),100000,0))
        store.save(listOf(Loan(id="petot-loan",party="Petot test lender",mode=LoanMode.I_OWE,total=5000,count=1,firstDue=today,frequency=Frequency.MONTHLY)))
        compose.setContent {CashaTheme(darkTheme=false){CashaApp(store,name)}}
        compose.waitUntil(5000){compose.onAllNodesWithText("Petot test lender").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("Petot test lender").onFirst().performClick()
        compose.onNodeWithText("Record payment").performClick()
        compose.onNodeWithText("Payment (PHP)").performTextInput("50")
        compose.onNodeWithText("Review payment").performClick()
        compose.onNodeWithText("Save payment").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("petot-popup").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("One loan down!").assertIsDisplayed()
        compose.runOnIdle {assertEquals(0L,store.load().single().remaining)}
        compose.onNodeWithContentDescription("Dismiss Petot message").performClick()
        compose.onNodeWithTag("loan-details-list").assertExists()
        compose.onNodeWithText("Close").performClick()
    }
}
