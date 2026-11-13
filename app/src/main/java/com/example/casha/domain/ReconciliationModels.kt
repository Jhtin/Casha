package com.example.casha.domain

import java.time.LocalDate

enum class PhysicalSettlementStatus {
    UNKNOWN,
    SETTLED_EXTERNALLY,
    DUPLICATE_DISCARDED,
    RESOLVED_TO_LOAN
}

data class UnreconciledPaymentRecord(
    val reconciliationId: String,
    val loanId: String,
    val loanParty: String,
    val paymentId: String,
    val installment: Int,
    val amountCentavos: Long,
    val isReceivable: Boolean,
    val walletId: String,
    val walletName: String,
    val date: LocalDate,
    val conflictReason: String,
    val physicalSettlement: PhysicalSettlementStatus = PhysicalSettlementStatus.UNKNOWN,
    val createdAtMs: Long = System.currentTimeMillis(),
    val resolvedAtMs: Long? = null,
    val resolutionNote: String? = null
)

enum class ThreeWayMergeStatus {
    AUTO_REBASED,
    ALREADY_SATISFIED,
    CONFLICT_UNRESOLVED
}

data class ThreeWayMergeResult(
    val status: ThreeWayMergeStatus,
    val rebasedMutations: List<CloudMutation> = emptyList(),
    val unreconciledPayments: List<UnreconciledPaymentRecord> = emptyList(),
    val explanation: String
)
