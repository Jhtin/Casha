package com.example.casha

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
import java.util.concurrent.atomic.AtomicBoolean

class ReceiptReviewUiTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope="receipt_review_${UUID.randomUUID()}"
    private val finance by lazy {FinanceStore(context,scope)}
    private val store by lazy {LoanStore(context,scope)}
    @After fun clean(){context.deleteSharedPreferences(scope);context.deleteSharedPreferences("finance_$scope")}
    @Test fun logExpenseButtonKeepsProofWalletDateAndActivity() {
        val day=LocalDate.now().minusDays(20)
        val suggestion=PaymentSuggestion("ui-expense",18400,day,"ORDER1","Receipt OCR","fixture.jpg")
        finance.addSuggestion(suggestion)
        val done=AtomicBoolean()
        val saved=androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {CashaTheme {
            if(saved.value)PaydayScreen(emptyList(),finance,openLoan={})
            else SuggestionDialog(store,emptyList(),finance,suggestion,{}) {done.set(true);saved.value=true}
        }}
        compose.onNodeWithText("Log as expense").performScrollTo().performClick()
        compose.waitUntil(5000){done.get()}
        val entry=finance.cashEntries().single()
        assertEquals(18400L,entry.amount);assertEquals(day,entry.date);assertFalse(entry.income)
        assertEquals("Cash on hand",entry.channel);assertTrue(finance.suggestions().isEmpty())
        assertEquals("fixture.jpg",finance.proof(entry.id)!!.getString("receipt"))
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Receipt · Ref ORDER1"))
        compose.onNodeWithText("Receipt · Ref ORDER1").assertIsDisplayed()
    }
    @Test fun reviewedPaymentButtonDeductsSelectedWalletAndKeepsLoanAudit() {
        val day=LocalDate.now()
        val loan=Loan(id="sample",party="Receipt test",mode=LoanMode.I_OWE,total=40000,count=2,firstDue=day,frequency=Frequency.MONTHLY)
        store.save(listOf(loan))
        val suggestion=PaymentSuggestion("ui-payment",18400,day,"PAY1","Receipt OCR","proof.jpg")
        finance.addSuggestion(suggestion)
        val done=AtomicBoolean()
        compose.setContent {CashaTheme {SuggestionDialog(store,listOf(loan),finance,suggestion,{}) {done.set(true)}}}
        val wallet=finance.moneyAccounts().first {it.id=="acc_bank"}
        compose.onNodeWithText(wallet.name).performScrollTo().performClick()
        compose.onNodeWithText("Record reviewed payment").performClick()
        compose.waitUntil(5000){done.get()}
        assertEquals(21600L,store.load().single().remaining)
        val link=finance.paymentLink("suggestion_ui-payment")!!
        assertEquals("acc_bank",link.accountId);assertEquals(18400L,link.amountCentavos)
        assertTrue(finance.pendingPaymentIntents().isEmpty());assertTrue(finance.cashEntries().isEmpty())
        assertEquals("proof.jpg",finance.proof("suggestion_ui-payment")!!.getString("receipt"))
        val profile=PaydayProfile(day,day.plusDays(15),0,0)
        assertEquals(-18400L,PaydayPlanner.accountBalances(finance.moneyAccounts(),finance.cashEntries(),profile,paymentLinks=finance.allPaymentLinks(),loans=store.load())[link.accountId])
    }
}
