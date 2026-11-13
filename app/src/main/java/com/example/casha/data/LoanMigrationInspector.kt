package com.example.casha.data

import com.example.casha.domain.*
import java.security.MessageDigest

object LoanMigrationInspector {

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun computeLoansFingerprint(loans: List<Loan>): String {
        val content = buildString {
            loans.sortedBy { it.id }.forEach { l ->
                append("${l.id}:${l.party}:${l.mode}:${l.total}:${l.count}:${l.firstDue}:${l.frequency};")
                l.payments.sortedBy { it.id }.forEach { p ->
                    append("${p.id}:${p.installment}:${p.amount}:${p.date};")
                }
                l.reversals.sortedBy { it.paymentId }.forEach { r ->
                    append("${r.paymentId}:${r.date}:${r.reason};")
                }
            }
        }
        return hash(content)
    }

    fun inspect(
        guestLoans: List<Loan>,
        cloudLoans: List<Loan>,
        cloudQueue: List<CloudMutation> = emptyList(),
        paymentLinks: Map<String, PaymentAccountLink> = emptyMap(),
        targetUid: String,
        sourceScope: String = "manual_loans"
    ): LoanMigrationManifest {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val loansToImport = mutableListOf<Loan>()
        val loansToSkip = mutableListOf<Loan>()
        val collidingLoans = mutableListOf<LoanImportItem>()

        val cloudLoansById = cloudLoans.associateBy { it.id }
        val queuedLoanIds = cloudQueue.map { it.after.id }.toSet()

        var totalPayments = 0
        var totalReversals = 0
        val linkedPaymentIds = mutableListOf<String>()

        guestLoans.sortedBy { it.id }.forEach { loan ->
            // Invariant Validation
            val invariantError = validateLoanInvariants(loan)
            if (invariantError != null) {
                errors += "Invalid guest loan '${loan.id}': $invariantError"
                return@forEach
            }

            // Mutation Sequence Validation
            val sequenceValid = runCatching {
                val mutations = CloudMutation.importHistory(loan)
                mutations.forEach { m ->
                    m.kind // Validate mutation transitions
                    CloudLoanDocument.encode(m.after, 1L, m.kind.name)
                }
                true
            }.getOrDefault(false)

            if (!sequenceValid) {
                errors += "Loan '${loan.id}' (${loan.party}) contains an invalid payment/reversal sequence that violates Firestore rules."
                return@forEach
            }

            val pIds = loan.payments.map { it.id }
            val linkedIdsForThisLoan = pIds.filter { it in paymentLinks }
            linkedPaymentIds.addAll(linkedIdsForThisLoan)

            if (loan.id in cloudLoansById) {
                val existing = cloudLoansById[loan.id]!!
                if (existing == loan) {
                    loansToSkip += loan
                    warnings += "Loan '${loan.party}' (${loan.id}) is already confirmed in cloud with identical payments. Skipping to prevent duplicate records."
                } else {
                    val item = LoanImportItem(
                        loan = loan,
                        collisionType = LoanCollisionType.DIFFERENT_CONFLICT,
                        existingCloudLoan = existing,
                        linkedWalletsCount = linkedIdsForThisLoan.size,
                        linkedPaymentIds = linkedIdsForThisLoan
                    )
                    collidingLoans += item
                    errors += "Conflicting loan ID collision: '${loan.id}' already exists in cloud with differing terms or payment history."
                }
            } else if (loan.id in queuedLoanIds) {
                warnings += "Loan '${loan.party}' (${loan.id}) is already queued for synchronization on this device."
                loansToSkip += loan
            } else {
                loansToImport += loan
                totalPayments += loan.payments.size
                totalReversals += loan.reversals.size
            }
        }

        val sourceFingerprint = computeLoansFingerprint(guestLoans)
        val targetCloudFingerprint = computeLoansFingerprint(cloudLoans)
        val manifestId = "loan_manifest_${hash("$sourceScope->$targetUid:$sourceFingerprint->$targetCloudFingerprint")}"

        return LoanMigrationManifest(
            manifestId = manifestId,
            sourceScope = sourceScope,
            targetUid = targetUid,
            sourceFingerprint = sourceFingerprint,
            targetCloudFingerprint = targetCloudFingerprint,
            loansToImport = loansToImport,
            loansToSkip = loansToSkip,
            collidingLoans = collidingLoans,
            totalPaymentsToImport = totalPayments,
            totalReversalsToImport = totalReversals,
            linkedPaymentIds = linkedPaymentIds.distinct(),
            errors = errors,
            warnings = warnings
        )
    }

    private fun validateLoanInvariants(loan: Loan): String? {
        if (!loan.id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
            return "ID '${loan.id}' must match [A-Za-z0-9_-]{1,100}"
        }
        if (loan.party.isBlank() || loan.party.length > 200) {
            return "Party name '${loan.party}' must be 1 to 200 non-blank characters"
        }
        if (loan.total <= 0L || loan.total > 10_000_000_000L) {
            return "Total ${loan.total} must be between 1 and 10,000,000,000"
        }
        if (loan.count !in 1..120) {
            return "Installment count ${loan.count} must be between 1 and 120"
        }
        if (loan.total < loan.count) {
            return "Total ${loan.total} must be >= count ${loan.count}"
        }
        if (loan.firstDue.year !in 1900..2200) {
            return "First due year ${loan.firstDue.year} must be between 1900 and 2200"
        }
        if (loan.frequency == Frequency.SEMIMONTHLY) {
            val day = loan.firstDue.dayOfMonth
            val isNextMonthFirstDay = loan.firstDue.plusDays(1).dayOfMonth == 1
            if (day != 15 && !isNextMonthFirstDay) {
                return "SEMIMONTHLY firstDue must be on the 15th or last day of the month"
            }
        }
        if (loan.payments.size > CloudLoanDocument.MAX_PAYMENTS) {
            return "Payments count ${loan.payments.size} exceeds maximum ${CloudLoanDocument.MAX_PAYMENTS}"
        }
        if (loan.reversals.size > CloudLoanDocument.MAX_PAYMENTS) {
            return "Reversals count ${loan.reversals.size} exceeds maximum ${CloudLoanDocument.MAX_PAYMENTS}"
        }
        if (loan.payments.any { !it.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) }) {
            return "Payment IDs must match [A-Za-z0-9_-]{1,100}"
        }
        if (loan.payments.any { it.date.year !in 1900..2200 }) {
            return "Payment dates must be between 1900 and 2200"
        }
        if (loan.reversals.any { it.reason.length > 500 }) {
            return "Reversal reason cannot exceed 500 characters"
        }
        return null
    }
}
