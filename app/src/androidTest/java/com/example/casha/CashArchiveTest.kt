package com.example.casha

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.*
import com.example.casha.domain.*
import com.example.casha.ui.CashaApp
import com.example.casha.ui.theme.CashaTheme
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

class CashArchiveTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="cash_archive_${UUID.randomUUID()}"
    private val store by lazy {LoanStore(context,name)}
    private val finance by lazy {FinanceStore(context,name)}
    @After fun cleanup(){context.deleteSharedPreferences(name);context.deleteSharedPreferences("finance_$name")}
    @Test fun finalPaymentArchivesAndReversalRestoresActiveLoan() {
        val today=LocalDate.now()
        val loan=Loan(id="archive-loan",party="Archive lender",mode=LoanMode.I_OWE,total=10000,count=1,firstDue=today,frequency=Frequency.MONTHLY).pay(0,5000,"partial",today)
        store.save(listOf(loan))
        compose.setContent {CashaTheme(darkTheme=false){CashaApp(store,name)}}
        compose.waitUntil(5000){compose.onAllNodesWithText("Archive lender").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("open-calendar").assertExists()
        compose.onNodeWithText("Payment calendar").assertDoesNotExist()
        compose.onAllNodesWithText("Archive lender").onFirst().performClick()
        compose.onNodeWithText("Record payment").performClick()
        compose.onNodeWithText("Payment (PHP)").performTextInput("50")
        compose.onNodeWithText("Review payment").performClick()
        compose.onNodeWithText("Save payment").performClick()
        compose.waitUntil(5000){store.load().single().remaining==0L}
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("loan-list").performScrollToNode(hasTestTag("loan-search"))
        compose.onNodeWithTag("loan-search").performTextInput("Archive lender")
        compose.onNodeWithTag("loan-list").performScrollToNode(hasText("No matching loans"))
        compose.onNodeWithText("No matching loans").assertIsDisplayed()
        compose.onNodeWithText("Show archived").performClick()
        compose.onNodeWithTag("loan-list").performScrollToNode(hasText("Paid in full"))
        compose.onNode(hasText("Archive lender") and !hasSetTextAction()).assertIsDisplayed()
        val paid=store.load().single()
        compose.runOnIdle {assertTrue(store.save(listOf(paid.reverse(paid.payments.last().id,today,"Correction")),listOf(paid)))}
        compose.onNodeWithTag("loan-list").performScrollToNode(hasText("Show archived"))
        compose.onNodeWithText("Show archived").performClick()
        compose.onNodeWithTag("loan-list").performScrollToNode(hasText("Archive lender") and !hasSetTextAction())
        compose.onAllNodes(hasText("Archive lender") and !hasSetTextAction()).onFirst().assertIsDisplayed()
        compose.runOnIdle {assertEquals(5000L,store.load().single().remaining)}
    }
    @Test fun logCashHasReceiptActionsAndSavesOnlyConfirmedIncome() {
        compose.setContent {CashaTheme(darkTheme=false){CashaApp(store,name)}}
        compose.onNodeWithText("Payday").performClick()
        compose.onNodeWithText("Log cash").performClick()
        compose.onNodeWithText("Log income").assertExists()
        compose.onNodeWithText("Import image").assertExists()
        compose.onNodeWithText("Take photo").assertExists()
        compose.onNodeWithText("Amount (PHP)").performTextInput("120.50")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {assertTrue(finance.cashEntries().isEmpty())}
        compose.onNodeWithText("Log cash").performClick()
        compose.onNodeWithText("Amount (PHP)").performTextInput("120.50")
        compose.onNodeWithText("Save cash entry").performClick()
        compose.waitUntil(5000){finance.cashEntries().size==1}
        compose.runOnIdle {val entry=finance.cashEntries().single();assertTrue(entry.income);assertEquals(12050L,entry.amount)}
    }
}
