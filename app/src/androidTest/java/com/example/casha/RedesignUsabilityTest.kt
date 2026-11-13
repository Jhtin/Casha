package com.example.casha

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.*
import com.example.casha.domain.*
import com.example.casha.ui.PaydayScreen
import com.example.casha.ui.theme.CashaTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class RedesignUsabilityTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope="redesign_${UUID.randomUUID()}"
    private val finance get()=FinanceStore(context,scope)
    @After fun cleanup(){context.deleteSharedPreferences("finance_$scope")}
    private fun picture(name:String) {
        val folder=File(context.filesDir,"phase_previews").apply {mkdirs()}
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(150)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {b->File(folder,name).outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    }
    @Test fun paydaySeparatesRecordsReservesAndAdviceWithoutSavingPayments() {
        val today=LocalDate.now()
        val loans=listOf(
            Loan(id="due",party="Home Credit",mode=LoanMode.I_OWE,total=600_000,count=3,firstDue=today.plusDays(5),frequency=Frequency.MONTHLY),
            Loan(id="overdue",party="Family loan",mode=LoanMode.I_OWE,total=100_000,count=1,firstDue=today.minusDays(2),frequency=Frequency.MONTHLY))
        val profile=PaydayProfile(today,today.plusDays(15),2_000_000,600_000,100_000,salaryAccountId="acc_bank")
        finance.saveProfile(profile)
        finance.addCash(CashEntry("food",false,25_000,today,"Food · groceries"))
        var opened="";var aiOpened=false
        compose.setContent {CashaTheme(darkTheme=false){Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){androidx.compose.foundation.layout.Box(Modifier.safeDrawingPadding()){PaydayScreen(loans,finance,onAssistant={aiOpened=true},openLoan={opened=it})}}}}
        compose.onNodeWithText("Payday overview").assertIsDisplayed()
        compose.onNodeWithText("Safe to spend").assertIsDisplayed();picture("redesign-payday-overview.png")
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Income & available cash"))
        compose.onNodeWithText("Income & available cash").assertExists()
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Upcoming obligations"))
        picture("redesign-payday-obligations.png")
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Family loan"))
        compose.onNodeWithText("Family loan").performClick()
        compose.runOnIdle {assertEquals("overdue",opened)}
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Allocation plan"))
        compose.onNodeWithText("Calculated reserves · no payments recorded").assertExists()
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Strategy tools"))
        picture("redesign-payday-plan.png")
        compose.onNodeWithText("Strategy tools").performClick()
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Payoff simulator"))
        compose.onNodeWithText("Payoff simulator").performClick()
        compose.onNodeWithText("Debt-free payoff simulator").assertIsDisplayed();picture("redesign-simulator.png")
        compose.onNodeWithText("Done").performClick()
        // Assistant navigation is provided by the main app tab, not the standalone payday screen.
        compose.onNodeWithText("Open AI assistant").assertDoesNotExist()
        compose.runOnIdle {assertFalse(aiOpened);assertEquals(profile,finance.profile());assertTrue(loans.all {it.payments.isEmpty()})}
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Budget details"))
        compose.onNodeWithText("Budget details").performClick()
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Save allocation plan"))
        compose.onNodeWithText("Save allocation plan").performClick()
        compose.runOnIdle {assertTrue(finance.preferences.contains("savedPlan"));assertEquals(profile,finance.profile())}
    }
    @Test fun smallDarkLargeTextKeepsShortfallAndCashActionVisible() {
        val today=LocalDate.now()
        val profile=PaydayProfile(today,today.plusDays(15),20_000,50_000,10_000)
        finance.saveProfile(profile)
        val loans=listOf(Loan(id="long-name",party="A lender with a long name that must wrap without hiding its balance",mode=LoanMode.I_OWE,total=5_000_000,count=1,firstDue=today,frequency=Frequency.MONTHLY))
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(3.375f,1.3f)){CashaTheme(darkTheme=true){Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){androidx.compose.foundation.layout.Box(Modifier.safeDrawingPadding()){PaydayScreen(loans,finance,openLoan={})}}}}}
        compose.onNodeWithText("Shortfall:",substring=true).assertIsDisplayed();picture("redesign-small-dark-shortfall.png")
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Log cash"))
        compose.onNodeWithText("Log cash").assertIsDisplayed()
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Upcoming obligations"))
        picture("redesign-small-dark-obligations.png")
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Allocation plan"))
        compose.onNodeWithText("Allocation plan").assertExists()
    }
}
