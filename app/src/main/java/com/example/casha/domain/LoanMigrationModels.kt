package com.example.casha.domain

import java.security.MessageDigest

enum class LoanMigrationStage {
    PREPARED,
    LOCAL_ENQUEUED,
    UPLOADING,
    CLOUD_ACKNOWLEDGED,
    VERIFIED,
    COMPLETED,
    FAILED,
    CONFLICT_PAUSED
}

enum class LoanCollisionType {
    NONE,
    IDENTICAL_EXISTING,
    DIFFERENT_CONFLICT
}

data class LoanImportItem(
    val loan: Loan,
    val collisionType: LoanCollisionType,
    val existingCloudLoan: Loan? = null,
    val linkedWalletsCount: Int = 0,
    val linkedPaymentIds: List<String> = emptyList()
)

data class LoanMigrationManifest(
    val manifestId: String,
    val sourceScope: String,
    val targetUid: String,
    val sourceFingerprint: String,
    val targetCloudFingerprint: String,
    val loansToImport: List<Loan>,
    val loansToSkip: List<Loan>,
    val collidingLoans: List<LoanImportItem>,
    val totalPaymentsToImport: Int,
    val totalReversalsToImport: Int,
    val linkedPaymentIds: List<String>,
    val errors: List<String>,
    val warnings: List<String>
) {
    val isReady: Boolean get() = errors.isEmpty() && collidingLoans.isEmpty()
}

enum class LoanCloudSyncState {
    PENDING_LOCAL,
    UPLOADING,
    ACKNOWLEDGED,
    FAILED,
    CONFLICT
}

data class PerLoanJournalState(
    val loanId: String,
    val totalMutations: Int,
    val confirmedMutations: Int,
    val status: LoanCloudSyncState,
    val cloudRevision: Long = 0L,
    val errorMessage: String? = null
)

data class LoanMigrationJournalEntry(
    val operationId: String,
    val manifestId: String,
    val timestampMs: Long,
    val schemaVersion: Int,
    val stage: LoanMigrationStage,
    val sourceScope: String,
    val targetUid: String,
    val perLoanStates: Map<String, PerLoanJournalState>,
    val errorMessage: String? = null
)

data class LoanMigrationResult(
    val success: Boolean,
    val stage: LoanMigrationStage,
    val loansEnqueued: Int,
    val paymentsEnqueued: Int,
    val reversalsEnqueued: Int,
    val loansSkipped: Int,
    val errorMessage: String? = null
)
