package com.example.casha.data

import com.example.casha.domain.*
import java.security.MessageDigest

object MigrationInspector {

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    fun inspect(
        source: LedgerSnapshot,
        target: LedgerSnapshot,
        loans: List<Loan>,
        userChoices: Map<String, WalletMappingAction> = emptyMap(),
        options: MigrationOptions = MigrationOptions()
    ): MigrationManifest = inspect(
        source = source,
        target = target,
        sourceLoans = loans,
        targetLoans = emptyList(),
        userChoices = userChoices,
        options = options
    )

    fun inspect(
        source: LedgerSnapshot,
        target: LedgerSnapshot,
        sourceLoans: List<Loan> = emptyList(),
        targetLoans: List<Loan> = emptyList(),
        userChoices: Map<String, WalletMappingAction> = emptyMap(),
        options: MigrationOptions = MigrationOptions()
    ): MigrationManifest {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 1. Build Wallet Mapping Plan
        val targetWalletsById = target.wallets.associateBy { it.id }
        val targetWalletsByName = target.wallets.associateBy { it.name.trim().lowercase() }
        val mappingEntries = mutableListOf<WalletMappingEntry>()
        val idCollisions = mutableListOf<String>()
        val nameCollisions = mutableListOf<String>()
        val remappedWalletIds = mutableMapOf<String, String>()
        val channelRemappings = mutableMapOf<String, String>()

        source.wallets.sortedBy { it.id }.forEach { sw ->
            val userChoice = userChoices[sw.id]
            if (sw.id in targetWalletsById) {
                val tw = targetWalletsById[sw.id]!!
                if (sw.name.equals(tw.name, ignoreCase = true) && sw.isSavings == tw.isSavings) {
                    mappingEntries += WalletMappingEntry(
                        sourceWalletId = sw.id,
                        sourceWalletName = sw.name,
                        resolvedTargetWalletId = tw.id,
                        resolvedTargetWalletName = tw.name,
                        action = WalletMappingAction.PRESERVE_EXACT,
                        reason = "Matching wallet ID and metadata; retained target identity."
                    )
                } else {
                    // ID collision with conflicting metadata
                    idCollisions += sw.id
                    val deterministicRemappedId = "acc_remap_${sw.id}_${hash(sw.name)}"
                    val remappedName = "${sw.name} (Imported)"
                    remappedWalletIds[sw.id] = deterministicRemappedId
                    channelRemappings[sw.name] = remappedName
                    mappingEntries += WalletMappingEntry(
                        sourceWalletId = sw.id,
                        sourceWalletName = sw.name,
                        resolvedTargetWalletId = deterministicRemappedId,
                        resolvedTargetWalletName = remappedName,
                        action = WalletMappingAction.REMAP_COLLISION_ID,
                        reason = "Wallet ID collision with conflicting metadata in target (target: '${tw.name}', isSavings=${tw.isSavings}); remapped to '$deterministicRemappedId'."
                    )
                }
            } else {
                val nameNormalized = sw.name.trim().lowercase()
                if (nameNormalized in targetWalletsByName) {
                    val tw = targetWalletsByName[nameNormalized]!!
                    nameCollisions += sw.name
                    if (userChoice == WalletMappingAction.MAP_TO_TARGET) {
                        remappedWalletIds[sw.id] = tw.id
                        mappingEntries += WalletMappingEntry(
                            sourceWalletId = sw.id,
                            sourceWalletName = sw.name,
                            resolvedTargetWalletId = tw.id,
                            resolvedTargetWalletName = tw.name,
                            action = WalletMappingAction.MAP_TO_TARGET,
                            reason = "User-directed mapping to target account '${tw.name}'."
                        )
                    } else {
                        // Default safety: Never silently combine unrelated physical accounts sharing a common name!
                        val distinctName = "${sw.name} (Device)"
                        channelRemappings[sw.name] = distinctName
                        mappingEntries += WalletMappingEntry(
                            sourceWalletId = sw.id,
                            sourceWalletName = sw.name,
                            resolvedTargetWalletId = sw.id,
                            resolvedTargetWalletName = distinctName,
                            action = WalletMappingAction.CREATE_DISTINCT,
                            reason = "Distinct physical account sharing display name '${sw.name}' with target; imported as separate wallet."
                        )
                    }
                } else {
                    // Unique wallet; imported directly
                    mappingEntries += WalletMappingEntry(
                        sourceWalletId = sw.id,
                        sourceWalletName = sw.name,
                        resolvedTargetWalletId = sw.id,
                        resolvedTargetWalletName = sw.name,
                        action = WalletMappingAction.PRESERVE_EXACT,
                        reason = "Unique wallet; imported with original identity."
                    )
                }
            }
        }

        val walletPlan = WalletMappingPlan(
            entries = mappingEntries,
            idCollisions = idCollisions,
            nameCollisions = nameCollisions,
            remappedWalletIds = remappedWalletIds,
            channelRemappings = channelRemappings
        )

        // 2. Validate Legacy Cash Channels
        val knownSourceWalletNames = source.wallets.map { it.name.trim().lowercase() }.toSet()
        var ambiguousChannelsCount = 0
        source.cashEntries.forEach { ce ->
            if (ce.channel.trim().lowercase() !in knownSourceWalletNames) {
                ambiguousChannelsCount++
                warnings += "Cash entry '${ce.id}' references legacy channel '${ce.channel}' which does not match any source wallet."
            }
        }

        // 3. Validate Cash Entries & Salary Deposits
        val targetCashById = target.cashEntries.associateBy { it.id }
        val cashToImport = mutableListOf<CashEntry>()
        val cashToSkip = mutableListOf<CashEntry>()
        val ambiguousSalaries = mutableListOf<AmbiguousSalaryDeposit>()
        var duplicateCashCount = 0
        var conflictingCashCount = 0
        var duplicateSalaryCount = 0

        source.cashEntries.sortedBy { it.id }.forEach { ce ->
            if (ce.id in targetCashById) {
                val te = targetCashById[ce.id]!!
                if (ce.amount == te.amount && ce.income == te.income && ce.date == te.date) {
                    duplicateCashCount++
                    cashToSkip += ce
                } else {
                    conflictingCashCount++
                    errors += "Conflicting cash entry ID collision: '${ce.id}' exists in target with differing content (Source: ${ce.amount} income=${ce.income}; Target: ${te.amount} income=${te.income})."
                }
            } else {
                val isCanonicalSalary = ce.isSalaryRelated && ce.id.startsWith("salary_cutoff_")
                val identicalTargetSalary = if (isCanonicalSalary) {
                    val resolvedChannel = channelRemappings[ce.channel] ?: ce.channel
                    target.cashEntries.find {
                        it.isSalaryRelated && it.id.startsWith("salary_cutoff_") &&
                        it.date == ce.date && it.amount == ce.amount &&
                        (it.channel.equals(ce.channel, ignoreCase = true) || it.channel.equals(resolvedChannel, ignoreCase = true))
                    }
                } else null

                val sameDateDifferentSalary = if (isCanonicalSalary && identicalTargetSalary == null) {
                    target.cashEntries.find {
                        it.isSalaryRelated && it.id.startsWith("salary_cutoff_") && it.date == ce.date
                    }
                } else null

                if (identicalTargetSalary != null) {
                    val resolution = options.salaryResolutions[ce.id]
                        ?: if (options.preserveAmbiguousSalaries) SalaryResolutionAction.KEEP_BOTH else null

                    ambiguousSalaries += AmbiguousSalaryDeposit(
                        sourceEntryId = ce.id,
                        sourceAmount = ce.amount,
                        sourceChannel = ce.channel,
                        date = ce.date,
                        existingTargetEntryId = identicalTargetSalary.id,
                        resolution = resolution
                    )

                    if (resolution == SalaryResolutionAction.KEEP_BOTH) {
                        warnings += "Preserving distinct salary deposit '${ce.id}' (${ce.amount} in ${ce.channel}) matching target deposit properties per user instruction."
                        val resolvedChannel = channelRemappings[ce.channel] ?: ce.channel
                        cashToImport += if (resolvedChannel != ce.channel) ce.copy(channel = resolvedChannel) else ce
                    } else if (resolution == SalaryResolutionAction.TREAT_AS_DUPLICATE) {
                        duplicateSalaryCount++
                        warnings += "Target already contains an identical salary deposit for cutoff date ${ce.date} of ${ce.amount} in ${ce.channel} (skipping source deposit duplicate '${ce.id}' to prevent carryover inflation)."
                        cashToSkip += ce
                    } else {
                        // Unresolved: flagged for mandatory user review
                        duplicateSalaryCount++
                        warnings += "Target already contains an identical salary deposit for cutoff date ${ce.date} of ${ce.amount} in ${ce.channel} (skipping source deposit duplicate '${ce.id}' to prevent carryover inflation)."
                        warnings += "Ambiguous salary deposit '${ce.id}' requires explicit user choice ('Keep both' or 'Treat as duplicate') before migration completion."
                        cashToSkip += ce
                    }
                } else {
                    if (sameDateDifferentSalary != null) {
                        warnings += "Preserving distinct salary deposit '${ce.id}' (${ce.amount} in ${ce.channel}) on ${ce.date} alongside existing target deposit of ${sameDateDifferentSalary.amount} in ${sameDateDifferentSalary.channel}."
                    }
                    val resolvedChannel = channelRemappings[ce.channel] ?: ce.channel
                    cashToImport += if (resolvedChannel != ce.channel) ce.copy(channel = resolvedChannel) else ce
                }
            }
        }

        // 4. Validate Payment Links
        val targetLinks = target.paymentLinks
        val linksToImport = mutableListOf<PaymentAccountLink>()
        val linksToSkip = mutableListOf<PaymentAccountLink>()
        var orphanedLinksCount = 0
        val sourceLoanPaymentMap = sourceLoans.flatMap { l -> l.payments.map { p -> p.id to (p to l) } }.toMap()
        val targetLoanPaymentIds = targetLoans.flatMap { it.payments }.map { it.id }.toSet()

        source.paymentLinks.toSortedMap().forEach { (paymentId, link) ->
            if (paymentId in targetLinks) {
                val tl = targetLinks[paymentId]!!
                if (tl == link) {
                    linksToSkip += link
                } else {
                    errors += "Conflicting payment link for payment '$paymentId': source points to loan '${link.loanId}', target points to loan '${tl.loanId}'."
                }
            } else {
                val sourcePaymentAndLoan = sourceLoanPaymentMap[paymentId]
                val paymentAmount = if (link.amountCentavos > 0L) link.amountCentavos else sourcePaymentAndLoan?.first?.amount ?: 0L
                val paymentDate = link.date ?: sourcePaymentAndLoan?.first?.date
                val isReceivable = link.isReceivable || (sourcePaymentAndLoan?.second?.mode == LoanMode.OWED_TO_ME)

                if (paymentId in targetLoanPaymentIds) {
                    // Loan payment already available in target
                } else if (sourcePaymentAndLoan != null) {
                    // Provisional local payment effect: loan is in source/guest storage, not target yet
                    warnings += "Payment link '$paymentId' (${paymentAmount} centavos) depends on guest loan '${link.loanId}', which is not yet present in the target account. Provisional local wallet accounting will be enabled."
                } else {
                    orphanedLinksCount++
                    warnings += "Payment link for payment '$paymentId' does not match any known payment on active loans."
                }

                val resolvedAccountId = remappedWalletIds[link.accountId] ?: link.accountId
                val resolvedAccountName = channelRemappings[link.accountName] ?: link.accountName
                linksToImport += link.copy(
                    accountId = resolvedAccountId,
                    accountName = resolvedAccountName,
                    amountCentavos = paymentAmount,
                    date = paymentDate,
                    isReceivable = isReceivable
                )
            }
        }

        // 5. Validate Payday History
        val targetHistoryIds = target.paydayHistory.map { it.id }.toSet()
        val historyToImport = mutableListOf<HistoricalCutoff>()
        val historyToSkip = mutableListOf<HistoricalCutoff>()
        source.paydayHistory.sortedBy { it.id }.forEach { hc ->
            if (hc.id in targetHistoryIds) {
                historyToSkip += hc
            } else {
                val resolvedSalaryAcc = hc.salaryAccountId?.let { remappedWalletIds[it] ?: it }
                historyToImport += hc.copy(salaryAccountId = resolvedSalaryAcc)
            }
        }

        // 6. Profile Action
        val profileAction = if (!target.hasProfile || (target.profile?.salary ?: 0L) == 0L) {
            ProfileTransferAction.ADOPT_SOURCE
        } else {
            ProfileTransferAction.PRESERVE_TARGET
        }

        val validation = MigrationValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            duplicateCashEntriesCount = duplicateCashCount,
            conflictingCashEntriesCount = conflictingCashCount,
            orphanedPaymentLinksCount = orphanedLinksCount,
            duplicateSalaryDepositsCount = duplicateSalaryCount,
            ambiguousLegacyChannelsCount = ambiguousChannelsCount,
            ambiguousSalaryDeposits = ambiguousSalaries
        )

        // 7. Deterministic Manifest Hash
        val manifestFingerprint = buildString {
            append("${source.scope}->${target.scope}:")
            append("wallets=${source.wallets.map { it.id }.sorted()}:")
            append("cash=${source.cashEntries.map { it.id }.sorted()}:")
            append("links=${source.paymentLinks.keys.sorted()}:")
            append("plan=${mappingEntries.map { "${it.sourceWalletId}->${it.resolvedTargetWalletId}:${it.action.name}" }}:")
            append("salRes=${options.salaryResolutions.toSortedMap()}")
        }
        val manifestId = "manifest_${hash(manifestFingerprint)}"

        return MigrationManifest(
            manifestId = manifestId,
            sourceScope = source.scope,
            targetScope = target.scope,
            sourceSnapshotFingerprint = source.computeFingerprint(),
            targetSnapshotFingerprint = target.computeFingerprint(),
            walletMappingPlan = walletPlan,
            cashEntriesToImport = cashToImport,
            cashEntriesToSkip = cashToSkip,
            paymentLinksToImport = linksToImport,
            paymentLinksToSkip = linksToSkip,
            paydayHistoryToImport = historyToImport,
            paydayHistoryToSkip = historyToSkip,
            profileTransferAction = profileAction,
            validation = validation,
            salaryResolutions = options.salaryResolutions
        )
    }
}
