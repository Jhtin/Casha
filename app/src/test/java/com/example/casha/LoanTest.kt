package com.example.casha

import com.example.casha.domain.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LoanTest {
    private fun loan(total: Long = 10_000, count: Int = 3) = Loan(
        party = "Lender", mode = LoanMode.I_OWE, total = total, count = count,
        firstDue = LocalDate.of(2026, 1, 31), frequency = Frequency.MONTHLY)

    @Test fun installmentsConserveEveryCent() {
        val schedule = loan().installments()
        assertEquals(listOf(3334L, 3333L, 3333L), schedule.map { it.amount })
        assertEquals(10_000L, schedule.sumOf { it.amount })
    }
    @Test fun monthlyScheduleClampsFebruaryWithoutDriftingMarch() {
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), loan().installments().map { it.due.toString() })
        assertEquals(LocalDate.of(2028, 2, 29), loan().copy(firstDue = LocalDate.of(2028, 1, 31)).installments()[1].due)
    }
    @Test fun fortnightlyMeansFourteenDays() {
        assertEquals(LocalDate.of(2026, 2, 14), loan().copy(frequency = Frequency.FORTNIGHTLY).installments()[1].due)
    }
    @Test fun partialPaymentChangesOnlyItsInstallment() {
        val updated = loan().pay(0, 1000, "payment-1", LocalDate.of(2026, 1, 1))
        assertEquals(9000L, updated.remaining)
        assertEquals(2334L, updated.installments()[0].remaining)
        assertEquals(3333L, updated.installments()[1].remaining)
    }
    @Test fun repeatedOperationIsIdempotent() {
        val date = LocalDate.of(2026, 1, 1)
        val updated = loan().pay(0, 1000, "same-id", date)
        assertEquals(updated, updated.pay(0, 1000, "same-id", date))
    }
    @Test(expected = IllegalArgumentException::class) fun conflictingOperationIsRejected() {
        val date = LocalDate.of(2026, 1, 1)
        loan().pay(0, 1000, "same-id", date).pay(0, 2000, "same-id", date)
    }
    @Test(expected = IllegalArgumentException::class) fun overpaymentIsRejected() {
        loan().pay(0, 3335, "payment-1", LocalDate.of(2026, 1, 1))
    }
    @Test fun moneyParsingIsExactAndRejectsInvalidValues() {
        assertEquals(12345L, parseMoney("123.45"))
        listOf("0", "-1", "1.234", "NaN", "1e3", "100000000.01").forEach { assertNull(parseMoney(it)) }
    }
    @Test fun twiceMonthlyUsesFifteenthAndActualMonthEnd() {
        val schedule = loan().copy(firstDue = LocalDate.of(2026, 2, 15), frequency = Frequency.SEMIMONTHLY)
        assertEquals(listOf("2026-02-15", "2026-02-28", "2026-03-15"), schedule.installments().map { it.due.toString() })
        assertEquals(10_000L, schedule.installments().sumOf { it.amount })
    }
    @Test fun twiceMonthlyCanStartAtLeapMonthEndAndCrossYear() {
        assertEquals(listOf("2028-02-29", "2028-03-15", "2028-03-31"),
            loan().copy(firstDue = LocalDate.of(2028, 2, 29), frequency = Frequency.SEMIMONTHLY).installments().map { it.due.toString() })
        assertEquals(listOf("2026-12-31", "2027-01-15", "2027-01-31"),
            loan().copy(firstDue = LocalDate.of(2026, 12, 31), frequency = Frequency.SEMIMONTHLY).installments().map { it.due.toString() })
    }
    @Test(expected = IllegalArgumentException::class) fun twiceMonthlyRejectsAmbiguousFirstDate() {
        loan().copy(firstDue = LocalDate.of(2026, 1, 30), frequency = Frequency.SEMIMONTHLY)
    }
    @Test fun unpaidLoanCanBeRescheduledWithoutChangingIdentity() {
        val original = loan()
        val edited = original.edit("New name", 20_000, 2, LocalDate.of(2026, 2, 15), Frequency.SEMIMONTHLY)
        assertEquals(original.id, edited.id)
        assertEquals(20_000L, edited.remaining)
        assertEquals("New name", edited.party)
    }
    @Test fun paidLoanCanBeRenamedWithoutChangingLedger() {
        val paid = loan().pay(0, 1000, "p1", LocalDate.of(2026, 1, 1))
        val edited = paid.edit("Updated lender", paid.total, paid.count, paid.firstDue, paid.frequency)
        assertEquals(paid.payments, edited.payments)
        assertEquals(paid.remaining, edited.remaining)
    }
    @Test(expected = IllegalArgumentException::class) fun paidScheduleCannotBeRewritten() {
        val paid = loan().pay(0, 1000, "p1", LocalDate.of(2026, 1, 1))
        paid.edit(paid.party, paid.total, paid.count, paid.firstDue.plusDays(1), paid.frequency)
    }
    @Test fun reversalRestoresBalanceAndRetainsOriginalPayment() {
        val date = LocalDate.of(2026, 1, 1)
        val paid = loan().pay(0, 3334, "p1", date)
        val reversed = paid.reverse("p1", date, "Wrong loan")
        assertEquals(10_000L, reversed.remaining)
        assertEquals(paid.payments, reversed.payments)
        assertEquals(1, reversed.reversals.size)
        assertEquals(reversed, reversed.reverse("p1", date, "Wrong loan"))
        assertEquals(9000L, reversed.pay(0, 1000, "p2", date).remaining)
    }
    @Test(expected = IllegalArgumentException::class) fun reversalNeedsReason() {
        val date = LocalDate.of(2026, 1, 1)
        loan().pay(0, 1000, "p1", date).reverse("p1", date, " ")
    }
    @Test(expected = IllegalArgumentException::class) fun cannotReverseUnknownPayment() {
        loan().reverse("missing", LocalDate.of(2026, 1, 1), "Mistake")
    }
    @Test(expected = IllegalArgumentException::class) fun reversalCannotPredatePayment() {
        loan().pay(0, 1000, "p1", LocalDate.of(2026, 1, 2))
            .reverse("p1", LocalDate.of(2026, 1, 1), "Mistake")
    }
    @Test(expected = IllegalArgumentException::class) fun reversingAllPaymentsDoesNotUnlockHistoricalSchedule() {
        val date = LocalDate.of(2026, 1, 1)
        val reversed = loan().pay(0, 1000, "p1", date).reverse("p1", date, "Mistake")
        reversed.edit(reversed.party, 20_000, reversed.count, reversed.firstDue, reversed.frequency)
    }
}
