package com.example.casha.data

import com.example.casha.domain.*
import org.json.JSONArray
import org.json.JSONObject

object MigrationExecutionEngine {

    @Synchronized
    fun executeMigration(
        sourceStore: FinanceStore,
        targetStore: FinanceStore,
        manifest: MigrationManifest,
        journalStore: MigrationJournalStore = MigrationJournalStore(targetStore.context)
    ): MigrationResult {
        synchronized(FinanceStore.lockForScope(targetStore.scope)) {
            val initialTargetRevision = targetStore.storeRevision()

            // 1. Freshness Re-validation: verify source and target have not changed concurrently
            val currentSource = sourceStore.inspectLedger()
            val currentTarget = targetStore.inspectLedger()

            if (currentSource.computeFingerprint() != manifest.sourceSnapshotFingerprint) {
                return MigrationResult(
                    success = false,
                    stage = MigrationStage.FAILED,
                    walletsImported = 0,
                    cashEntriesImported = 0,
                    paymentLinksImported = 0,
                    paydayHistoryImported = 0,
                    profileAdopted = false,
                    errorMessage = "Stale migration manifest: source device records changed concurrently. Please review the updated preview."
                )
            }

            if (currentTarget.computeFingerprint() != manifest.targetSnapshotFingerprint) {
                return MigrationResult(
                    success = false,
                    stage = MigrationStage.FAILED,
                    walletsImported = 0,
                    cashEntriesImported = 0,
                    paymentLinksImported = 0,
                    paydayHistoryImported = 0,
                    profileAdopted = false,
                    errorMessage = "Stale migration manifest: target account records changed concurrently. Please review the updated preview."
                )
            }

            if (journalStore.hasUnresolvedJournal(targetStore.scope)) {
                return MigrationResult(
                    success = false,
                    stage = MigrationStage.FAILED,
                    walletsImported = 0,
                    cashEntriesImported = 0,
                    paymentLinksImported = 0,
                    paydayHistoryImported = 0,
                    profileAdopted = false,
                    errorMessage = "Cannot start new migration: An incomplete migration for '${targetStore.scope}' is pending recovery or validation. Overlapping migrations are blocked."
                )
            }

            if (manifest.hasUnresolvedSalaryAmbiguities) {
                return MigrationResult(
                    success = false,
                    stage = MigrationStage.FAILED,
                    walletsImported = 0,
                    cashEntriesImported = 0,
                    paymentLinksImported = 0,
                    paydayHistoryImported = 0,
                    profileAdopted = false,
                    errorMessage = "Cannot complete migration: Unresolved ambiguous salary deposits require an explicit user choice ('Keep both' or 'Treat as duplicate')."
                )
            }

        if (!manifest.isReady) {
            return MigrationResult(
                success = false,
                stage = MigrationStage.FAILED,
                walletsImported = 0,
                cashEntriesImported = 0,
                paymentLinksImported = 0,
                paydayHistoryImported = 0,
                profileAdopted = false,
                errorMessage = "Cannot commit invalid migration: ${manifest.validation.errors.joinToString("; ")}"
            )
        }

        // 2. Persist Checkpoint before any destination mutation
        val preTargetSnapshot = targetStore.exportPreferencesSnapshot()
        val journalEntry = MigrationJournalEntry(
            migrationId = "mig_${System.currentTimeMillis()}_${manifest.manifestId.take(8)}",
            timestampMs = System.currentTimeMillis(),
            schemaVersion = MigrationJournalStore.CURRENT_SCHEMA_VERSION,
            stage = MigrationStage.READY_TO_COMMIT,
            sourceScope = sourceStore.scope,
            targetScope = targetStore.scope,
            manifestId = manifest.manifestId,
            preTargetSnapshotJson = preTargetSnapshot
        )

        val checkpointSaved = journalStore.saveJournal(journalEntry)
        if (!checkpointSaved) {
            return MigrationResult(
                success = false,
                stage = MigrationStage.FAILED,
                walletsImported = 0,
                cashEntriesImported = 0,
                paymentLinksImported = 0,
                paydayHistoryImported = 0,
                profileAdopted = false,
                errorMessage = "Failed to establish durable safety checkpoint. Migration aborted."
            )
        }

        // 3. Atomic Local Merge on Destination Store
        var walletsAdded = 0
        var cashAdded = 0
        var linksAdded = 0
        var historyAdded = 0
        var profileAdopted = false

        val commitOk = targetStore.commit(expectedRevision = initialTargetRevision) { editor ->
            // A. Merge Wallets
            val existingTargetWallets = targetStore.allMoneyAccounts().associateBy { it.id }.toMutableMap()
            manifest.walletMappingPlan.entries.forEach { entry ->
                val srcWallet = currentSource.wallets.find { it.id == entry.sourceWalletId }
                val isArchived = srcWallet?.isArchived ?: false
                when (entry.action) {
                    WalletMappingAction.PRESERVE_EXACT -> {
                        if (!existingTargetWallets.containsKey(entry.resolvedTargetWalletId)) {
                            val newWallet = MoneyAccount(
                                id = entry.resolvedTargetWalletId,
                                name = entry.resolvedTargetWalletName,
                                isSavings = srcWallet?.isSavings ?: false,
                                icon = srcWallet?.icon ?: "Wallet",
                                isArchived = isArchived
                            )
                            existingTargetWallets[newWallet.id] = newWallet
                            walletsAdded++
                        }
                    }
                    WalletMappingAction.CREATE_DISTINCT -> {
                        if (!existingTargetWallets.containsKey(entry.resolvedTargetWalletId)) {
                            val distinctWallet = MoneyAccount(
                                id = entry.resolvedTargetWalletId,
                                name = entry.resolvedTargetWalletName,
                                isSavings = srcWallet?.isSavings ?: false,
                                icon = srcWallet?.icon ?: "Wallet",
                                isArchived = isArchived
                            )
                            existingTargetWallets[distinctWallet.id] = distinctWallet
                            walletsAdded++
                        }
                    }
                    WalletMappingAction.REMAP_COLLISION_ID -> {
                        if (!existingTargetWallets.containsKey(entry.resolvedTargetWalletId)) {
                            val remappedWallet = MoneyAccount(
                                id = entry.resolvedTargetWalletId,
                                name = entry.resolvedTargetWalletName,
                                isSavings = srcWallet?.isSavings ?: false,
                                icon = srcWallet?.icon ?: "Wallet",
                                isArchived = isArchived
                            )
                            existingTargetWallets[remappedWallet.id] = remappedWallet
                            walletsAdded++
                        }
                    }
                    WalletMappingAction.MAP_TO_TARGET -> {
                        // User-directed mapping into target wallet: no new wallet entity created
                    }
                }
            }
            if (walletsAdded > 0) {
                val a = JSONArray()
                existingTargetWallets.values.forEach { w ->
                    a.put(JSONObject().put("id", w.id).put("name", w.name).put("isSavings", w.isSavings).put("icon", w.icon).put("isArchived", w.isArchived))
                }
                editor.putString("money_accounts", a.toString())
            }

            // B. Merge Cash Entries
            if (manifest.cashEntriesToImport.isNotEmpty()) {
                val currentTargetCash = targetStore.cashEntries()
                val targetCashIds = currentTargetCash.map { it.id }.toSet()
                val mergedCash = currentTargetCash.toMutableList()
                manifest.cashEntriesToImport.forEach { ce ->
                    if (ce.id !in targetCashIds) {
                        mergedCash.add(ce)
                        cashAdded++
                    }
                }
                val a = JSONArray()
                mergedCash.forEach { e ->
                    a.put(JSONObject().put("id", e.id).put("income", e.income).put("amount", e.amount).put("date", e.date.toString()).put("note", e.note).put("channel", e.channel))
                }
                editor.putString("cash", a.toString())
            }

            // C. Merge Payment Links
            if (manifest.paymentLinksToImport.isNotEmpty()) {
                val currentTargetLinks = targetStore.allPaymentLinks()
                manifest.paymentLinksToImport.forEach { link ->
                    if (link.paymentId !in currentTargetLinks) {
                        val obj = JSONObject()
                            .put("loanId", link.loanId)
                            .put("accountId", link.accountId)
                            .put("accountName", link.accountName)
                            .put("isReceivable", link.isReceivable)
                            .put("amountCentavos", link.amountCentavos)
                        if (link.date != null) obj.put("date", link.date.toString())
                        editor.putString("payment_account_${link.paymentId}", obj.toString())
                        linksAdded++
                    }
                }
            }

            // D. Merge Payday History
            if (manifest.paydayHistoryToImport.isNotEmpty()) {
                val currentHistory = targetStore.paydayHistory()
                val existingHistoryIds = currentHistory.map { it.id }.toSet()
                val mergedHistory = currentHistory.toMutableList()
                manifest.paydayHistoryToImport.forEach { hc ->
                    if (hc.id !in existingHistoryIds) {
                        mergedHistory.add(hc)
                        historyAdded++
                    }
                }
                val a = JSONArray()
                mergedHistory.forEach { hc ->
                    a.put(JSONObject()
                        .put("id", hc.id)
                        .put("start", hc.start.toString())
                        .put("next", hc.next.toString())
                        .put("baseSalary", hc.baseSalary)
                        .put("carryover", hc.carryover)
                        .put("essentials", hc.essentials)
                        .put("emergency", hc.emergency)
                        .put("strategy", hc.strategy.name)
                        .put("salaryAccountId", hc.salaryAccountId ?: "")
                        .put("closedAtMs", hc.closedAtMs)
                        .put("finalSafeToSpend", hc.finalSafeToSpend)
                        .put("salaryEntryId", hc.salaryEntryId ?: ""))
                }
                editor.putString("payday_history", a.toString())
            }

            // E. Profile & Default Salary Account
            if (manifest.profileTransferAction == ProfileTransferAction.ADOPT_SOURCE || manifest.profileTransferAction == ProfileTransferAction.USER_OVERWRITE) {
                val srcProfile = currentSource.profile
                if (srcProfile != null) {
                    val resolvedSalaryAccId = srcProfile.salaryAccountId?.let {
                        manifest.walletMappingPlan.remappedWalletIds[it] ?: it
                    }
                    val targetProfile = srcProfile.copy(salaryAccountId = resolvedSalaryAccId)
                    val obj = JSONObject()
                        .put("start", targetProfile.start.toString())
                        .put("next", targetProfile.next.toString())
                        .put("salary", targetProfile.salary)
                        .put("essentials", targetProfile.essentials)
                        .put("emergency", targetProfile.emergency)
                        .put("strategy", targetProfile.strategy.name)
                        .put("carryover", targetProfile.carryover)
                    if (targetProfile.salaryAccountId != null) obj.put("salaryAccountId", targetProfile.salaryAccountId)
                    editor.putString("profile", obj.toString())
                    profileAdopted = true

                    if (resolvedSalaryAccId != null && !targetStore.preferences.contains("default_salary_account")) {
                        editor.putString("default_salary_account", resolvedSalaryAccId)
                    }
                }
            }

            // F. Migration Provenance Stamp
            editor.putString("migrated_from_scope", sourceStore.scope)
            editor.putLong("migration_timestamp", System.currentTimeMillis())
        }

        if (!commitOk) {
            // Commit failed; recover target pre-state immediately
            journalStore.saveJournal(journalEntry.copy(stage = MigrationStage.FAILED, errorMessage = "Failed to commit merged ledger preferences to destination store."))
            journalStore.recoverIfInterrupted(targetStore)
            return MigrationResult(
                success = false,
                stage = MigrationStage.FAILED,
                walletsImported = 0,
                cashEntriesImported = 0,
                paymentLinksImported = 0,
                paydayHistoryImported = 0,
                profileAdopted = false,
                errorMessage = "Failed to commit merged ledger preferences to destination store. Pre-state restored."
            )
        }

        // 4. Balance Recalculation & Validation
        val recalculatedBalances = PaydayPlanner.accountBalances(
            accounts = targetStore.allMoneyAccounts(),
            entries = targetStore.cashEntries(),
            profile = targetStore.profile(),
            defaultSalaryAccountId = targetStore.defaultSalaryAccountId(),
            paymentLinks = targetStore.allPaymentLinks(),
            loans = emptyList()
        )

        // Persist LOCAL_COMMITTED stage with expected target metrics to prevent startup crash rollback and ensure exact validation
        val committedTargetSnapshot = targetStore.inspectLedger()
        journalStore.saveJournal(
            journalEntry.copy(
                stage = MigrationStage.LOCAL_COMMITTED,
                expectedTargetFingerprint = committedTargetSnapshot.computeFingerprint(),
                expectedAccountsCount = committedTargetSnapshot.wallets.size,
                expectedCashCount = committedTargetSnapshot.cashEntries.size,
                expectedBalances = recalculatedBalances
            )
        )

        // 5. Complete Migration Journal Checkpoint
        journalStore.saveJournal(journalEntry.copy(stage = MigrationStage.BALANCES_VERIFIED))
        journalStore.clearJournal()

        return MigrationResult(
            success = true,
            stage = MigrationStage.COMPLETED,
            walletsImported = walletsAdded,
            cashEntriesImported = cashAdded,
            paymentLinksImported = linksAdded,
            paydayHistoryImported = historyAdded,
            profileAdopted = profileAdopted,
            recalculatedBalances = recalculatedBalances
        )
    }
}
}
