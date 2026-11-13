package com.example.casha.domain

enum class MutationKind { CREATE, EDIT, PAYMENT, REVERSAL, DELETE }
data class CloudMutation(val before: Loan?, val after: Loan, val deleting:Boolean=false) {
    val kind: MutationKind get() {
        if(deleting){require(before!=null&&before==after&&before.payments.isEmpty()&&before.reversals.isEmpty());return MutationKind.DELETE}
        if (before == null) { require(after.payments.isEmpty() && after.reversals.isEmpty()); return MutationKind.CREATE }
        require(before.id == after.id && before.mode == after.mode)
        if (after.payments.size == before.payments.size + 1) {
            val p = after.payments.last()
            require(before.pay(p.installment, p.amount, p.id, p.date) == after)
            return MutationKind.PAYMENT
        }
        if (after.reversals.size == before.reversals.size + 1) {
            val r = after.reversals.last()
            require(before.reverse(r.paymentId, r.date, r.reason) == after)
            return MutationKind.REVERSAL
        }
        require(before.edit(after.party, after.total, after.count, after.firstDue, after.frequency) == after)
        return MutationKind.EDIT
    }
    fun alreadyApplied(remote: Loan): Boolean = when (kind) {
        MutationKind.DELETE -> false
        MutationKind.PAYMENT -> remote.payments.any { it == after.payments.last() }
        MutationKind.REVERSAL -> remote.reversals.any { it == after.reversals.last() }
        else -> remote == after
    }
    companion object {
        fun importHistory(loan: Loan): List<CloudMutation> {
            var current = loan.copy(payments = emptyList(), reversals = emptyList())
            val result = mutableListOf(CloudMutation(null, current))

            val remainingPayments = loan.payments.toMutableList()
            val remainingReversals = loan.reversals.toMutableList()

            while (remainingPayments.isNotEmpty() || remainingReversals.isNotEmpty()) {
                val eligiblePayments = remainingPayments.filter { p ->
                    p.amount <= current.installments()[p.installment].remaining
                }

                val eligibleReversals = remainingReversals.filter { r ->
                    current.payments.any { it.id == r.paymentId }
                }

                val nextActionIsReversal = if (eligiblePayments.isEmpty()) {
                    if (eligibleReversals.isNotEmpty()) true
                    else throw IllegalStateException("Deadlock in mutation sequencing: no eligible payment or reversal")
                } else if (eligibleReversals.isEmpty()) {
                    false
                } else {
                    val blockedInstallments = remainingPayments.filter { it !in eligiblePayments }.map { it.installment }.toSet()
                    val unblockingReversal = eligibleReversals.find { r ->
                        val p = current.payments.find { it.id == r.paymentId }
                        p != null && p.installment in blockedInstallments
                    }

                    if (unblockingReversal != null) {
                        true
                    } else {
                        val earliestPay = eligiblePayments.minByOrNull { it.date }!!
                        val earliestRev = eligibleReversals.minByOrNull { it.date }!!
                        earliestRev.date < earliestPay.date
                    }
                }

                if (nextActionIsReversal) {
                    val blockedInstallments = remainingPayments.filter { it !in eligiblePayments }.map { it.installment }.toSet()
                    val selectedRev = eligibleReversals.find { r ->
                        val p = current.payments.find { it.id == r.paymentId }
                        p != null && p.installment in blockedInstallments
                    } ?: eligibleReversals.minByOrNull { it.date }!!

                    remainingReversals.remove(selectedRev)
                    val next = current.reverse(selectedRev.paymentId, selectedRev.date, selectedRev.reason)
                    result += CloudMutation(current, next)
                    current = next
                } else {
                    val selectedPay = eligiblePayments.minByOrNull { it.date }!!
                    remainingPayments.remove(selectedPay)
                    val next = current.pay(selectedPay.installment, selectedPay.amount, selectedPay.id, selectedPay.date)
                    result += CloudMutation(current, next)
                    current = next
                }
            }

            return result
        }
    }
}
