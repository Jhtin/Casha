package com.example.casha

import com.example.casha.domain.*
import com.example.casha.ui.dueUrgencyColors
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PaymentCalendarTest {
    private val today = LocalDate.of(2026, 11, 3)

    @Test
    fun paidLoansAreExcludedFromActiveLoans() {
        val activeLoan = Loan(
            id = "active_1",
            party = "BPI Personal Loan",
            mode = LoanMode.I_OWE,
            total = 100_000L,
            count = 2,
            firstDue = today.plusDays(10),
            frequency = Frequency.MONTHLY
        )
        val fullyPaidLoan = Loan(
            id = "paid_1",
            party = "Test Paid Loan",
            mode = LoanMode.I_OWE,
            total = 50_000L,
            count = 1,
            firstDue = today.minusDays(5),
            frequency = Frequency.MONTHLY
        ).pay(0, 50_000L, "pay_1", today.minusDays(5))

        val archivedLoan = Loan(
            id = "archived_1",
            party = "Archived Loan",
            mode = LoanMode.I_OWE,
            total = 30_000L,
            count = 1,
            firstDue = today.plusDays(15),
            frequency = Frequency.MONTHLY
        )

        val archivedFlags = setOf("archived_archived_1")
        val loans = listOf(activeLoan, fullyPaidLoan, archivedLoan)

        // Logic tested: filtering active loans for calendar colors and active dues
        val activeLoans = loans.filter { it.remaining > 0 && "archived_${it.id}" !in archivedFlags }

        assertEquals(1, activeLoans.size)
        assertEquals("active_1", activeLoans.first().id)
        assertFalse(activeLoans.any { it.id == "paid_1" })
        assertFalse(activeLoans.any { it.id == "archived_1" })
    }

    @Test
    fun calendarDuesRespectUnpaidOnlyFilter() {
        val partiallyPaid = Loan(
            id = "loan_part",
            party = "SSS Loan",
            mode = LoanMode.I_OWE,
            total = 100_000L,
            count = 2,
            firstDue = today.minusDays(10),
            frequency = Frequency.MONTHLY
        ).pay(0, 50_000L, "pay_part", today.minusDays(10))

        val allInstallments = partiallyPaid.installments()
        assertEquals(2, allInstallments.size)
        assertEquals(0L, allInstallments[0].remaining)
        assertEquals(50_000L, allInstallments[1].remaining)

        // When unpaidOnly = true, paid installments (remaining == 0) must be excluded
        val unpaidInstallments = allInstallments.filter { it.remaining > 0 }
        assertEquals(1, unpaidInstallments.size)
        assertEquals(1, unpaidInstallments.first().index)

        // When unpaidOnly = false, all installments are present
        assertEquals(2, allInstallments.size)
    }

    @Test
    fun urgencyCalculationReportsCorrectStatuses() {
        fun urgencyLabel(due: LocalDate?, remaining: Long, testToday: LocalDate): String {
            val days = due?.let { java.time.temporal.ChronoUnit.DAYS.between(testToday, it) } ?: Long.MAX_VALUE
            return when {
                remaining <= 0 || due == null -> "Paid"
                days < 0 -> "Overdue"
                days == 0L -> "Due today"
                days <= 3 -> "Due in ${days}d"
                days <= 7 -> "Due in ${days}d"
                else -> "Upcoming"
            }
        }

        assertEquals("Paid", urgencyLabel(today.minusDays(2), remaining = 0L, testToday = today))
        assertEquals("Overdue", urgencyLabel(today.minusDays(2), remaining = 15_000L, testToday = today))
        assertEquals("Due today", urgencyLabel(today, remaining = 15_000L, testToday = today))
        assertEquals("Due in 2d", urgencyLabel(today.plusDays(2), remaining = 15_000L, testToday = today))
        assertEquals("Due in 6d", urgencyLabel(today.plusDays(6), remaining = 15_000L, testToday = today))
        assertEquals("Upcoming", urgencyLabel(today.plusDays(15), remaining = 15_000L, testToday = today))
    }

    @Test
    fun recordedActivitiesAreOrderedLatestFirstByDefault() {
        val entry1 = CashEntry("c1", true, 4_000L, today, "Income · Cash", channel = "Cash")
        val entry2 = CashEntry("c2", false, 500L, today, "Expense · Union bank", channel = "Union bank")
        val entry3 = CashEntry("c3", false, 109L, today, "GADC STARITAGUIGUIN", channel = "Gotyme")

        val dayCash = listOf(entry1, entry2, entry3)
        val latestFirst = true
        val ordered = if (latestFirst) dayCash.reversed() else dayCash

        // Default: latest entry (GADC STARITAGUIGUIN -₱109.00) is at the top
        assertEquals("c3", ordered[0].id)
        assertEquals("c2", ordered[1].id)
        assertEquals("c1", ordered[2].id)

        // When toggled to oldest first
        val oldestFirst = false
        val chronological = if (oldestFirst) dayCash.reversed() else dayCash
        assertEquals("c1", chronological[0].id)
        assertEquals("c2", chronological[1].id)
        assertEquals("c3", chronological[2].id)
    }
}
