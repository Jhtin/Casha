package com.example.casha

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.casha.ui.CapturedPaymentConfirmDialog
import org.junit.Rule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.*
import com.example.casha.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class WithdrawalCaptureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reviewShowsCashFeeAndTransferInsteadOfExpense() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "test_withdrawal_ui_${UUID.randomUUID()}"
        val store = FinanceStore(context, scope)
        try {
            store.addMoneyAccount(MoneyAccount("gotyme", "Gotyme"))
            store.updateMoneyAccount(store.moneyAccounts().first { it.id == "acc_cash" }.copy(name = "Cash"))
            val payment = CapturedPayment("review", 481800, "ATM", "ph.com.gotyme", "Gotyme", LocalDate.now(),
                "Money withdrawn P4818.00 withdrawn from your account at ATM. A P18.00 fee was charged for this transaction.")
            store.addCapturedPayment(payment)
            compose.setContent { MaterialTheme {
                CapturedPaymentConfirmDialog(payment, store,
                    onConfirm = { note, channel, amount -> assertTrue(store.confirmCapturedPayment(payment.id, note, channel, amount)) }, onDismiss = {})
            } }
            compose.onNodeWithText("Review ATM withdrawal").assertIsDisplayed()
            compose.onNodeWithText("Cash received: ₱4,800.00").assertExists()
            compose.onNodeWithText("Withdrawal fee: ₱18.00").assertExists()
            compose.onNodeWithText("Transfer to: Cash").assertExists()
            compose.onNodeWithText("Confirm & log expense").assertDoesNotExist()
            compose.onNodeWithText("Confirm transfer & fee").performClick()
            compose.runOnIdle { assertEquals(3, store.cashEntries().size) }
        } finally { context.deleteSharedPreferences("finance_$scope") }
    }

    @Test fun missingCashWalletExplainsProblemAndDisablesConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "test_withdrawal_missing_${UUID.randomUUID()}"
        val store = FinanceStore(context, scope)
        try {
            store.saveMoneyAccounts(listOf(MoneyAccount("gotyme", "Gotyme")))
            val payment = CapturedPayment("missing", 201600, "ATM", "ph.com.gotyme", "Gotyme", LocalDate.now(),
                "P2016.00 withdrawn from your account at ATM. A P16.00 fee was charged.")
            store.addCapturedPayment(payment)
            compose.setContent { MaterialTheme {
                CapturedPaymentConfirmDialog(payment, store, onConfirm = { _, _, _ -> fail("Must not save without a destination") }, onDismiss = {})
            } }
            compose.onNodeWithText("Transfer to: No cash wallet available").assertExists()
            compose.onNodeWithText("Confirm transfer & fee").assertIsNotEnabled()
            compose.runOnIdle { assertTrue(store.cashEntries().isEmpty()) }
        } finally { context.deleteSharedPreferences("finance_$scope") }
    }

    @Test fun withdrawalIsAtomicIdempotentTransferWithSeparateFeeAndGroupedReversal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "test_withdrawal_${UUID.randomUUID()}"
        val store = FinanceStore(context, scope)
        try {
            assertTrue(store.addMoneyAccount(MoneyAccount("gotyme", "Gotyme")))
            val date = LocalDate.now()
            assertTrue(store.addCapturedPayment(CapturedPayment("atm", 48100, "ATM", "ph.com.gotyme", "Gotyme", date,
                "Money withdrawn P4818.00 withdrawn from your account at ATM. A P18.00 fee was charged for this transaction.")))
            assertFalse(store.confirmCapturedPayment("atm"))
            assertTrue(store.cashEntries().isEmpty())
            assertFalse(store.confirmCapturedPayment("atm", channel = "Gotyme", amountCentavos = 48100))
            assertTrue(store.confirmCapturedPayment("atm", channel = "Gotyme", amountCentavos = 480000))
            assertTrue(store.confirmCapturedPayment("atm", channel = "Gotyme", amountCentavos = 480000))
            val entries = store.cashEntries()
            assertEquals(3, entries.size)
            assertEquals(-481800L, entries.filter { it.channel == "Gotyme" }.sumOf { if(it.income) it.amount else -it.amount })
            assertEquals(480000L, entries.filter { it.channel == MoneyAccount.DEFAULT_CASH }.sumOf { if(it.income) it.amount else -it.amount })
            val profile = PaydayProfile(date, date.plusDays(14), 1000000, 0)
            assertEquals(998200L, PaydayPlanner.calculate(profile, emptyList(), emptyMap(), entries, date).cash)
            assertEquals(1800L, PaydayPlanner.categoryBreakdown(profile, entries, date).sumOf { it.amount })
            assertFalse(store.updateCash(entries.first().copy(amount = 1)))
            assertFalse(store.deleteCash(entries.first().id))
            assertTrue(store.reverseCash(entries.first().id))
            assertFalse(store.reverseCash(entries.first().id))
            assertEquals(6, store.cashEntries().size)
            assertEquals(1000000L, PaydayPlanner.calculate(profile, emptyList(), emptyMap(), store.cashEntries(), date).cash)
            assertEquals(0L, store.cashEntries().sumOf { if(it.income) it.amount else -it.amount })
        } finally { context.deleteSharedPreferences("finance_$scope") }
    }
}
