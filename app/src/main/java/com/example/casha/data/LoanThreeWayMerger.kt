package com.example.casha.data

import com.example.casha.domain.*

object LoanThreeWayMerger {

    fun merge(
        base: List<Loan>,
        localQueue: List<CloudMutation>,
        remoteLoan: Loan,
        paymentLinks: Map<String, PaymentAccountLink> = emptyMap()
    ): ThreeWayMergeResult {
        val loanMutations = localQueue.filter { it.after.id == remoteLoan.id }
        if (loanMutations.isEmpty()) {
            return ThreeWayMergeResult(
                status = ThreeWayMergeStatus.ALREADY_SATISFIED,
                explanation = "No queued mutations for loan '${remoteLoan.id}'."
            )
        }

        val localLoan = loanMutations.last().after
        val baseLoan = base.find { it.id == remoteLoan.id }

        val basePaymentIds = baseLoan?.payments?.map { it.id }?.toSet() ?: emptySet()
        val localNewPayments = localLoan.payments.filter { it.id !in basePaymentIds }
        val localNewReversals = localLoan.reversals.filter { r ->
            baseLoan?.reversals?.none { it.paymentId == r.paymentId } ?: true
        }

        // 1. Content collision: Same payment ID exists on remote with differing attributes
        val contentCollisions = localNewPayments.filter { p ->
            remoteLoan.payments.any {
                it.id == p.id && (it.amount != p.amount || it.installment != p.installment || it.date != p.date)
            }
        }
        if (contentCollisions.isNotEmpty()) {
            val conflicts = contentCollisions.map { p ->
                val link = paymentLinks[p.id]
                UnreconciledPaymentRecord(
                    reconciliationId = "recon_${System.currentTimeMillis()}_${p.id}",
                    loanId = remoteLoan.id,
                    loanParty = remoteLoan.party,
                    paymentId = p.id,
                    installment = p.installment,
                    amountCentavos = p.amount,
                    isReceivable = remoteLoan.mode == LoanMode.OWED_TO_ME,
                    walletId = link?.accountId ?: "unknown_wallet",
                    walletName = link?.accountName ?: "Unknown",
                    date = p.date,
                    conflictReason = "Payment ID collision: '${p.id}' already exists on cloud server with differing installment, date, or amount.",
                    physicalSettlement = PhysicalSettlementStatus.UNKNOWN
                )
            }
            return ThreeWayMergeResult(
                status = ThreeWayMergeStatus.CONFLICT_UNRESOLVED,
                unreconciledPayments = conflicts,
                explanation = "Identical payment ID collision with conflicting content."
            )
        }

        // Agreement edits and deletions need explicit review when their CAS failed.
        val origin = loanMutations.first().before ?: loanMutations.first().after
        val sameTerms = origin.mode == remoteLoan.mode && origin.total == remoteLoan.total &&
            origin.count == remoteLoan.count && origin.firstDue == remoteLoan.firstDue && origin.frequency == remoteLoan.frequency
        if (!sameTerms || loanMutations.any { it.kind == MutationKind.DELETE } ||
            loanMutations.any { it.kind == MutationKind.CREATE && it.after.party != remoteLoan.party } ||
            loanMutations.any { it.kind == MutationKind.EDIT && remoteLoan.party != it.before?.party && remoteLoan.party != it.after.party }) {
            return ThreeWayMergeResult(ThreeWayMergeStatus.CONFLICT_UNRESOLVED,
                explanation = "Agreement or deletion conflict requires explicit review.")
        }
        if (localNewReversals.any { local -> remoteLoan.reversals.any { it.paymentId == local.paymentId && it != local } }) {
            return ThreeWayMergeResult(ThreeWayMergeStatus.CONFLICT_UNRESOLVED,
                explanation = "The cloud reversal has different audit details.")
        }

        // Replay the durable command order, including reversals that free capacity for later payments.
        var current = remoteLoan
        val rebased = mutableListOf<CloudMutation>()
        val conflicts = mutableListOf<UnreconciledPaymentRecord>()
        var invalid = false
        loanMutations.forEach { mutation ->
            val before = current
            val replay = runCatching {
                when (mutation.kind) {
                    MutationKind.PAYMENT -> mutation.after.payments.last().let { p -> current.pay(p.installment,p.amount,p.id,p.date) }
                    MutationKind.REVERSAL -> mutation.after.reversals.last().let { r -> current.reverse(r.paymentId,r.date,r.reason) }
                    MutationKind.EDIT -> current.edit(mutation.after.party,mutation.after.total,mutation.after.count,mutation.after.firstDue,mutation.after.frequency)
                    MutationKind.CREATE -> current
                    MutationKind.DELETE -> error("Deletion requires review")
                }
            }
            if (replay.isFailure) {
                invalid = true
                if (mutation.kind == MutationKind.PAYMENT) {
                    val payment = mutation.after.payments.last()
                    val link = paymentLinks[payment.id]
                    conflicts += UnreconciledPaymentRecord(
                        reconciliationId = "recon_${remoteLoan.id}_${payment.id}", loanId=remoteLoan.id,
                        loanParty=remoteLoan.party,paymentId=payment.id,installment=payment.installment,
                        amountCentavos=payment.amount,isReceivable=remoteLoan.mode==LoanMode.OWED_TO_ME,
                        walletId=link?.accountId ?: "unknown_wallet",walletName=link?.accountName ?: "Unknown",
                        date=payment.date,conflictReason="Installment capacity exceeded or incompatible payment history; the payment needs review.")
                }
            } else {
                current = replay.getOrThrow()
                if (current != before) rebased += CloudMutation(before,current)
            }
        }
        if (invalid) return ThreeWayMergeResult(ThreeWayMergeStatus.CONFLICT_UNRESOLVED,
            unreconciledPayments=conflicts,explanation="Incompatible installment capacity or causal history.")
        return if (rebased.isEmpty()) ThreeWayMergeResult(ThreeWayMergeStatus.ALREADY_SATISFIED,
            explanation="All local commands are already present with matching content.")
        else ThreeWayMergeResult(ThreeWayMergeStatus.AUTO_REBASED,rebasedMutations=rebased,
            explanation="Compatible commands replayed onto the remote loan in causal order.")
    }
}
