package com.example.casha

import com.example.casha.data.*
import com.example.casha.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class WithdrawalLedgerTest {
    @Test fun renamedCashWalletReceivesWithdrawalExactlyOnce() {
        val store = FinanceStore(null, "test_cash_renamed", FakeSharedPreferences())
        val cash = store.moneyAccounts().first { it.id == "acc_cash" }
        assertTrue(store.updateMoneyAccount(cash.copy(name = "Cash")))
        assertTrue(store.addMoneyAccount(MoneyAccount("gotyme", "Gotyme")))
        assertTrue(store.addCapturedPayment(CapturedPayment("atm_cash", 201600, "ATM", "ph.com.gotyme", "Gotyme", LocalDate.now(),
            "P2016.00 withdrawn from your account at ATM. A P16.00 fee was charged.")))
        repeat(2) { assertTrue(store.confirmCapturedPayment("atm_cash", channel = "Gotyme", amountCentavos = 200000)) }
        assertEquals(3, store.cashEntries().size)
        assertEquals(200000L, store.cashEntries().single { it.income }.amount)
        assertEquals("Cash", store.cashEntries().single { it.income }.channel)
        assertEquals(-201600L, store.cashEntries().filter { it.channel == "Gotyme" }.sumOf { if (it.income) it.amount else -it.amount })
        assertTrue(store.capturedPayments().isEmpty())
    }

    @Test fun missingCashWalletDoesNotConsumeCaptureOrWritePartialTransfer() {
        val store = FinanceStore(null, "test_cash_missing", FakeSharedPreferences())
        assertTrue(store.saveMoneyAccounts(listOf(MoneyAccount("gotyme", "Gotyme"), MoneyAccount("gcash", "Gcash"))))
        assertTrue(store.addCapturedPayment(CapturedPayment("atm_missing", 201600, "ATM", "ph.com.gotyme", "Gotyme", LocalDate.now(),
            "P2016.00 withdrawn from your account at ATM. A P16.00 fee was charged.")))
        assertFalse(store.confirmCapturedPayment("atm_missing", channel = "Gotyme", amountCentavos = 200000))
        assertTrue(store.cashEntries().isEmpty())
        assertEquals(1, store.capturedPayments().size)
    }

    @Test fun withdrawalIsAtomicIdempotentTransferWithSeparateFeeAndGroupedReversal() {
        val scope = "test_withdrawal_${UUID.randomUUID()}"
        val store = FinanceStore(null, scope, FakeSharedPreferences())
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
        } finally { /* synthetic in-memory preferences only */ }
    }
}
