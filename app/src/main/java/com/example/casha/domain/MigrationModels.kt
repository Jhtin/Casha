package com.example.casha.domain

import java.security.MessageDigest

enum class MigrationStage {
    PREPARING,
    VALIDATING,
    READY_TO_COMMIT,
    LOCAL_COMMITTED,
    BALANCES_VERIFIED,
    QUEUED_FOR_CLOUD,
    COMPLETED,
    FAILED,
    ROLLED_BACK
}

enum class WalletMappingAction {
    PRESERVE_EXACT,       // Matching ID & identical metadata: retain target wallet
    CREATE_DISTINCT,      // Same name but different account: import as distinct wallet
    REMAP_COLLISION_ID,   // Same ID but conflicting metadata: remap incoming ID
    MAP_TO_TARGET         // User-directed mapping into target account
}

data class WalletMappingEntry(
    val sourceWalletId: String,
    val sourceWalletName: String,
    val resolvedTargetWalletId: String,
    val resolvedTargetWalletName: String,
    val action: WalletMappingAction,
    val reason: String
)

data class WalletMappingPlan(
    val entries: List<WalletMappingEntry>,
    val idCollisions: List<String>,
    val nameCollisions: List<String>,
    val remappedWalletIds: Map<String, String>, // sourceId -> targetId
    val channelRemappings: Map<String, String>  // sourceName -> targetName
)

data class LedgerSnapshot(
    val scope: String,
    val hasProfile: Boolean,
    val profile: PaydayProfile?,
    val wallets: List<MoneyAccount>,
    val cashEntries: List<CashEntry>,
    val paymentLinks: Map<String, PaymentAccountLink>,
    val paydayHistory: List<HistoricalCutoff>,
    val defaultSalaryAccountId: String?
) {
    fun computeFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val content = buildString {
            append("scope=$scope;")
            append("hasProfile=$hasProfile;")
            if (profile != null) {
                append("profile=${profile.start},${profile.next},${profile.salary},${profile.essentials},${profile.emergency},${profile.strategy},${profile.salaryAccountId},${profile.carryover};")
            }
            append("wallets=")
            wallets.sortedBy { it.id }.forEach { w ->
                append("${w.id}:${w.name}:${w.isSavings}:${w.icon}:${w.isArchived};")
            }
            append("cash=")
            cashEntries.sortedBy { it.id }.forEach { c ->
                append("${c.id}:${c.income}:${c.amount}:${c.date}:${c.channel}:${c.note};")
            }
            append("links=")
            paymentLinks.toSortedMap().forEach { (k, v) ->
                append("$k:${v.loanId}:${v.accountId}:${v.accountName}:${v.isReceivable}:${v.amountCentavos}:${v.date};")
            }
            append("history=")
            paydayHistory.sortedBy { it.id }.forEach { h ->
                append("${h.id}:${h.start}:${h.next}:${h.baseSalary}:${h.carryover};")
            }
            append("defaultSalary=$defaultSalaryAccountId;")
        }
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

enum class ProfileTransferAction {
    ADOPT_SOURCE,      // Target has no profile; adopting source profile
    PRESERVE_TARGET,   // Target already has profile; keeping target
    USER_OVERWRITE     // Explicit user choice to overwrite target profile
}

enum class SalaryResolutionAction {
    KEEP_BOTH,
    TREAT_AS_DUPLICATE
}

data class AmbiguousSalaryDeposit(
    val sourceEntryId: String,
    val sourceAmount: Long,
    val sourceChannel: String,
    val date: java.time.LocalDate,
    val existingTargetEntryId: String,
    val resolution: SalaryResolutionAction? = null
)

data class MigrationValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
    val duplicateCashEntriesCount: Int,
    val conflictingCashEntriesCount: Int,
    val orphanedPaymentLinksCount: Int,
    val duplicateSalaryDepositsCount: Int,
    val ambiguousLegacyChannelsCount: Int,
    val ambiguousSalaryDeposits: List<AmbiguousSalaryDeposit> = emptyList(),
    val hasUnresolvedSalaryAmbiguities: Boolean = ambiguousSalaryDeposits.any { it.resolution == null }
) {
    val ambiguousSalaryDepositsCount: Int get() = ambiguousSalaryDeposits.size
}

data class MigrationOptions(
    val preserveAmbiguousSalaries: Boolean = false,
    val salaryResolutions: Map<String, SalaryResolutionAction> = emptyMap()
)

data class MigrationManifest(
    val manifestId: String,
    val sourceScope: String,
    val targetScope: String,
    val sourceSnapshotFingerprint: String,
    val targetSnapshotFingerprint: String,
    val walletMappingPlan: WalletMappingPlan,
    val cashEntriesToImport: List<CashEntry>,
    val cashEntriesToSkip: List<CashEntry>,
    val paymentLinksToImport: List<PaymentAccountLink>,
    val paymentLinksToSkip: List<PaymentAccountLink>,
    val paydayHistoryToImport: List<HistoricalCutoff>,
    val paydayHistoryToSkip: List<HistoricalCutoff>,
    val profileTransferAction: ProfileTransferAction,
    val validation: MigrationValidationResult,
    val salaryResolutions: Map<String, SalaryResolutionAction> = emptyMap()
) {
    val isReady: Boolean get() = validation.isValid
    val hasUnresolvedSalaryAmbiguities: Boolean get() = validation.hasUnresolvedSalaryAmbiguities
}

data class MigrationJournalEntry(
    val migrationId: String,
    val timestampMs: Long,
    val schemaVersion: Int,
    val stage: MigrationStage,
    val sourceScope: String,
    val targetScope: String,
    val manifestId: String,
    val preTargetSnapshotJson: String?,
    val errorMessage: String? = null,
    val expectedTargetFingerprint: String? = null,
    val expectedAccountsCount: Int? = null,
    val expectedCashCount: Int? = null,
    val expectedBalances: Map<String, Long>? = null
)

data class MigrationResult(
    val success: Boolean,
    val stage: MigrationStage,
    val walletsImported: Int,
    val cashEntriesImported: Int,
    val paymentLinksImported: Int,
    val paydayHistoryImported: Int,
    val profileAdopted: Boolean,
    val recalculatedBalances: Map<String, Long> = emptyMap(),
    val errorMessage: String? = null
)

class UnsupportedSchemaVersionException(message: String) : IllegalArgumentException(message)
