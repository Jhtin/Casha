package com.example.casha.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

enum class LoanMode { I_OWE, OWED_TO_ME }
enum class Frequency { MONTHLY, WEEKLY, FORTNIGHTLY, SEMIMONTHLY }
data class Payment(val id: String, val installment: Int, val amount: Long, val date: LocalDate)
data class PaymentReversal(val paymentId: String, val date: LocalDate, val reason: String)
data class Installment(val index: Int, val due: LocalDate, val amount: Long, val paid: Long) {
    val remaining get() = amount - paid
}
data class Loan(
    val id: String = UUID.randomUUID().toString(), val party: String, val mode: LoanMode,
    val total: Long, val count: Int, val firstDue: LocalDate, val frequency: Frequency,
    val payments: List<Payment> = emptyList(),
    val reversals: List<PaymentReversal> = emptyList()
) {
    init {
        require(party.isNotBlank())
        require(total in 1..MAX_AMOUNT && count in 1..120 && total >= count)
        require(payments.map { it.id }.distinct().size == payments.size)
        require(payments.all { it.installment in 0 until count && it.amount > 0 && it.amount <= total })
        require(reversals.all { it.reason.isNotBlank() })
        require(frequency != Frequency.SEMIMONTHLY || firstDue.dayOfMonth == 15 || firstDue.dayOfMonth == firstDue.lengthOfMonth())
        require(installments().all { it.paid <= it.amount })
    }
    fun installments(): List<Installment> = (0 until count).map { i ->
        val due = when (frequency) {
            Frequency.MONTHLY -> firstDue.plusMonths(i.toLong())
            Frequency.WEEKLY -> firstDue.plusWeeks(i.toLong())
            Frequency.FORTNIGHTLY -> firstDue.plusWeeks(i * 2L)
            Frequency.SEMIMONTHLY -> {
                val slot = i + if (firstDue.dayOfMonth == 15) 0 else 1
                val month = firstDue.withDayOfMonth(1).plusMonths(slot / 2L)
                month.withDayOfMonth(if (slot % 2 == 0) 15 else month.lengthOfMonth())
            }
        }
        Installment(i, due, total / count + if (i < total % count) 1 else 0,
            payments.filter { it.installment == i && reversals.none { r -> r.paymentId == it.id } }
                .fold(0L) { sum, p -> Math.addExact(sum, p.amount) })
    }
    val remaining get() = installments().sumOf { it.remaining }
    fun edit(party: String, total: Long, count: Int, firstDue: LocalDate, frequency: Frequency): Loan {
        // Keep historical installment identities and amounts stable once any payment was recorded.
        require(payments.isEmpty() || (total == this.total && count == this.count && firstDue == this.firstDue && frequency == this.frequency))
        return copy(party = party.trim(), total = total, count = count, firstDue = firstDue, frequency = frequency)
    }
    fun reverse(paymentId: String, date: LocalDate, reason: String): Loan {
        require(reason.isNotBlank())
        val existing = reversals.find { it.paymentId == paymentId }
        if (existing != null) {
            require(existing.date == date && existing.reason == reason.trim())
            return this
        }
        require(payments.any { it.id == paymentId && !date.isBefore(it.date) })
        return copy(reversals = reversals + PaymentReversal(paymentId, date, reason.trim()))
    }
    fun pay(index: Int, amount: Long, operationId: String, date: LocalDate): Loan {
        require(operationId.isNotBlank())
        payments.find { it.id == operationId }?.let {
            require(it.installment == index && it.amount == amount && it.date == date)
            return this
        }
        require(index in 0 until count && amount > 0 && amount <= installments()[index].remaining)
        return copy(payments = payments + Payment(operationId, index, amount, date))
    }
    companion object { const val MAX_AMOUNT = 10_000_000_000L }
}
fun parseMoney(text: String): Long? = try {
    if (text.length !in 1..20 || !text.matches(Regex("[0-9]+(\\.[0-9]{1,2})?"))) null
    else BigDecimal(text).movePointRight(2).longValueExact().takeIf { it in 1..Loan.MAX_AMOUNT }
} catch (_: ArithmeticException) { null }
