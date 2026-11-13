package com.example.casha.data

import android.content.Context
import com.example.casha.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

class PaymentReconciliationStore(
    val context: Context? = null,
    val targetUid: String,
    private val preferencesOverride: android.content.SharedPreferences? = null
) {
    companion object {
        fun hashUid(uid: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private val prefs by lazy {
        preferencesOverride ?: context?.getSharedPreferences(
            "payment_reconciliation_${hashUid(targetUid)}",
            Context.MODE_PRIVATE
        )
    }

    @Synchronized
    fun saveRecord(record: UnreconciledPaymentRecord): Boolean {
        val current = loadRecords().toMutableList()
        val index = current.indexOfFirst { it.reconciliationId == record.reconciliationId || it.paymentId == record.paymentId }
        if (index >= 0) {
            current[index] = record
        } else {
            current += record
        }
        return persist(current)
    }

    @Synchronized
    fun loadRecords(): List<UnreconciledPaymentRecord> {
        val raw = prefs?.getString("records", "[]") ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                UnreconciledPaymentRecord(
                    reconciliationId = obj.getString("reconciliationId"),
                    loanId = obj.getString("loanId"),
                    loanParty = obj.getString("loanParty"),
                    paymentId = obj.getString("paymentId"),
                    installment = obj.getInt("installment"),
                    amountCentavos = obj.getLong("amountCentavos"),
                    isReceivable = obj.getBoolean("isReceivable"),
                    walletId = obj.getString("walletId"),
                    walletName = obj.getString("walletName"),
                    date = LocalDate.parse(obj.getString("date")),
                    conflictReason = obj.getString("conflictReason"),
                    physicalSettlement = PhysicalSettlementStatus.valueOf(obj.getString("physicalSettlement")),
                    createdAtMs = obj.getLong("createdAtMs"),
                    resolvedAtMs = if (obj.has("resolvedAtMs")) obj.getLong("resolvedAtMs") else null,
                    resolutionNote = obj.optString("resolutionNote").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun loadUnresolvedRecords(): List<UnreconciledPaymentRecord> {
        return loadRecords().filter { it.physicalSettlement == PhysicalSettlementStatus.UNKNOWN }
    }

    @Synchronized
    fun resolveAsExternalExpense(
        recordId: String,
        financeStore: FinanceStore,
        note: String = "Settled external physical payment",
        repository: CloudLoanRepository? = null
    ): Boolean {
        val record = loadRecords().find { it.reconciliationId == recordId || it.paymentId == recordId } ?: return false
        if (record.physicalSettlement == PhysicalSettlementStatus.SETTLED_EXTERNALLY) return true
        if (record.physicalSettlement != PhysicalSettlementStatus.UNKNOWN) return false

        // 1. Create matching cash entry to preserve wallet balance deduction
        val cashEntry = CashEntry(
            id = "reconciled_cash_${record.paymentId}",
            income = record.isReceivable,
            amount = record.amountCentavos,
            date = record.date,
            channel = record.walletName,
            note = "External payment: ${record.loanParty} (${record.conflictReason})".take(200)
        )
        if (repository != null && !repository.discardConflictingMutation(record.loanId, record.paymentId)) return false
        val cashAdded = financeStore.settleProvisionalPayment(record.paymentId, cashEntry)
        if (!cashAdded) return false

        // 2. Remove provisional payment link

        // 3. Mark record as settled externally
        val updated = record.copy(
            physicalSettlement = PhysicalSettlementStatus.SETTLED_EXTERNALLY,
            resolvedAtMs = System.currentTimeMillis(),
            resolutionNote = note
        )
        return saveRecord(updated)
    }

    @Synchronized
    fun resolveAsDuplicateDiscard(
        recordId: String,
        financeStore: FinanceStore,
        repository: CloudLoanRepository? = null,
        note: String = "Discarded accidental duplicate entry"
    ): Boolean {
        val record = loadRecords().find { it.reconciliationId == recordId || it.paymentId == recordId } ?: return false
        if (record.physicalSettlement == PhysicalSettlementStatus.DUPLICATE_DISCARDED) return true
        if (record.physicalSettlement != PhysicalSettlementStatus.UNKNOWN) return false

        // 1. Remove provisional payment link (restores wallet balance)

        // 2. Remove from cloud loan repository queue if present
        if (repository != null && !repository.discardConflictingMutation(record.loanId, record.paymentId)) return false
        if (!financeStore.settleProvisionalPayment(record.paymentId, null)) return false

        // 3. Mark record as duplicate discarded
        val updated = record.copy(
            physicalSettlement = PhysicalSettlementStatus.DUPLICATE_DISCARDED,
            resolvedAtMs = System.currentTimeMillis(),
            resolutionNote = note
        )
        return saveRecord(updated)
    }

    @Synchronized
    fun resolveAsReassignedInstallment(
        recordId: String,
        targetInstallment: Int,
        repository: CloudLoanRepository,
        note: String? = null
    ): Boolean {
        val record = loadRecords().find { it.reconciliationId == recordId || it.paymentId == recordId } ?: return false
        if (record.physicalSettlement == PhysicalSettlementStatus.RESOLVED_TO_LOAN) return true
        if (record.physicalSettlement != PhysicalSettlementStatus.UNKNOWN) return false

        // Rebase mutation in repository onto new target installment
        val rebased = repository.reassignQueuedPaymentInstallment(record.loanId, record.paymentId, targetInstallment)
        if (!rebased) return false

        val updated = record.copy(
            installment = targetInstallment,
            physicalSettlement = PhysicalSettlementStatus.RESOLVED_TO_LOAN,
            resolvedAtMs = System.currentTimeMillis(),
            resolutionNote = note ?: "Reassigned to installment #${targetInstallment + 1}"
        )
        return saveRecord(updated)
    }

    private fun persist(records: List<UnreconciledPaymentRecord>): Boolean {
        val array = JSONArray()
        records.forEach { r ->
            val obj = JSONObject()
                .put("reconciliationId", r.reconciliationId)
                .put("loanId", r.loanId)
                .put("loanParty", r.loanParty)
                .put("paymentId", r.paymentId)
                .put("installment", r.installment)
                .put("amountCentavos", r.amountCentavos)
                .put("isReceivable", r.isReceivable)
                .put("walletId", r.walletId)
                .put("walletName", r.walletName)
                .put("date", r.date.toString())
                .put("conflictReason", r.conflictReason)
                .put("physicalSettlement", r.physicalSettlement.name)
                .put("createdAtMs", r.createdAtMs)
            if (r.resolvedAtMs != null) obj.put("resolvedAtMs", r.resolvedAtMs)
            if (r.resolutionNote != null) obj.put("resolutionNote", r.resolutionNote)
            array.put(obj)
        }
        return prefs?.edit()?.putString("records", array.toString())?.commit() ?: false
    }
}
