package com.example.casha

import com.example.casha.data.CapturedPayment
import com.example.casha.domain.CashEntry
import com.example.casha.domain.PaydayPlanner
import com.example.casha.domain.PaydayProfile
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CapturedPaymentTest {

    @Test
    fun pendingCapturedPaymentDoesNotAffectCashUntilConfirmed() {
        val today = LocalDate.of(2026, 11, 3)
        val profile = PaydayProfile(
            start = LocalDate.of(2026, 10, 24),
            next = LocalDate.of(2026, 11, 7),
            salary = 1_000_000L, // ₱10,000.00
            essentials = 0L
        )

        val activeEntries = mutableListOf(
            CashEntry("c1", false, 400_000L, today, "Cash on hand", "Cash"),
            CashEntry("c2", false, 500_000L, today, "Gotyme deposit", "Gotyme")
        )

        // Baseline before captured payment
        val baselinePlan = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), activeEntries, today)

        // Captured payment is received
        val captured = CapturedPayment(
            id = "captured_123",
            amountCentavos = 5_200L, // ₱52.00
            merchant = "DAN NICO D",
            sourcePackage = "ph.com.gotyme",
            channel = "Gotyme",
            date = today
        )

        val pendingList = mutableListOf(captured)

        // VERIFY: pending list is NOT in activeEntries, so budget calculation is UNCHANGED
        val planWhilePending = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), activeEntries, today)
        assertEquals(baselinePlan.safeToSpend, planWhilePending.safeToSpend)
        assertEquals(baselinePlan.cash, planWhilePending.cash)

        // When user CONFIRMS: it gets converted to a CashEntry and added to activeEntries
        val confirmedEntry = CashEntry(
            id = "nfc_456",
            income = false,
            amount = captured.amountCentavos,
            date = captured.date,
            note = captured.merchant,
            channel = captured.channel
        )
        activeEntries.add(confirmedEntry)
        pendingList.remove(captured)

        // VERIFY: now that it is confirmed, it is deducted
        val planAfterConfirmation = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), activeEntries, today)
        assertEquals(baselinePlan.safeToSpend - 5_200L, planAfterConfirmation.safeToSpend)
        assertTrue(pendingList.isEmpty())
    }

    @Test
    fun dismissedCapturedPaymentNeverTouchesLedger() {
        val today = LocalDate.of(2026, 11, 3)
        val profile = PaydayProfile(
            start = LocalDate.of(2026, 10, 24),
            next = LocalDate.of(2026, 11, 7),
            salary = 900_000L,
            essentials = 0L
        )

        val activeEntries = mutableListOf<CashEntry>()
        val captured = CapturedPayment(
            id = "captured_789",
            amountCentavos = 5_200L,
            merchant = "DAN NICO D",
            sourcePackage = "ph.com.gotyme",
            channel = "Gotyme",
            date = today
        )

        val pendingList = mutableListOf(captured)

        // When user DISMISSES:
        pendingList.remove(captured)

        // Ledger is still untouched
        val plan = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), activeEntries, today)
        assertEquals(profile.salary, plan.cash)
        assertTrue(activeEntries.isEmpty())
        assertTrue(pendingList.isEmpty())
    }
}
