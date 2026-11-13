package com.example.casha.data

import com.example.casha.domain.*
import java.time.LocalDate

/** Version 1 uses one bounded atomic loan aggregate so the rules can validate each ledger delta. */
object CloudLoanDocument {
    const val MAX_PAYMENTS = 500
    fun encode(loan: Loan, revision: Long, action: String): Map<String, Any> {
        require(loan.party.length <= 200 && loan.payments.size <= MAX_PAYMENTS)
        require(loan.firstDue.year in 1900..2200)
        require(loan.payments.all {it.date.year in 1900..2200}&&loan.reversals.all {it.date.year in 1900..2200})
        require(loan.id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(loan.payments.all { it.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) })
        require(loan.reversals.all { it.reason.length <= 500 })
        return mapOf(
            "schemaVersion" to 1L, "revision" to revision, "action" to action,
            "party" to loan.party, "mode" to loan.mode.name, "total" to loan.total,
            "count" to loan.count.toLong(), "firstDue" to loan.firstDue.toString(), "frequency" to loan.frequency.name,
            "payments" to loan.payments.map { mapOf("id" to it.id, "installment" to it.installment.toLong(), "amount" to it.amount, "date" to it.date.toString()) },
            "reversals" to loan.reversals.map { mapOf("paymentId" to it.paymentId, "paymentIndex" to loan.payments.indexOfFirst { p -> p.id == it.paymentId }.toLong(), "date" to it.date.toString(), "reason" to it.reason) },
            "paid" to loan.installments().filter { it.paid > 0 }.associate { it.index.toString() to it.paid },
            "paymentIds" to loan.payments.associate { it.id to true },
            "reversed" to loan.reversals.associate { it.paymentId to true }
        )
    }
    @Suppress("UNCHECKED_CAST")
    fun decode(id: String, data: Map<String, Any>): Loan {
        require((data["schemaVersion"] as Number).toInt() == 1)
        return Loan(id, data["party"] as String, LoanMode.valueOf(data["mode"] as String),
            (data["total"] as Number).toLong(), (data["count"] as Number).toInt(), LocalDate.parse(data["firstDue"] as String),
            Frequency.valueOf(data["frequency"] as String),
            (data["payments"] as List<Map<String, Any>>).map {
                Payment(it["id"] as String, (it["installment"] as Number).toInt(), (it["amount"] as Number).toLong(), LocalDate.parse(it["date"] as String))
            }, (data["reversals"] as List<Map<String, Any>>).map {
                PaymentReversal(it["paymentId"] as String, LocalDate.parse(it["date"] as String), it["reason"] as String)
            })
    }
}
