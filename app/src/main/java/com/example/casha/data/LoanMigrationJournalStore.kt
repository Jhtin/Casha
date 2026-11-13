package com.example.casha.data

import android.content.Context
import com.example.casha.domain.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class LoanMigrationJournalStore(
    val context: Context? = null,
    val targetUid: String
) {

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val JOURNAL_PREFIX = "loan_migration_journal_"

        fun hashUid(uid: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun journalFile(): File {
        val baseDir = context?.filesDir ?: File(System.getProperty("java.io.tmpdir", "."), "casha_checkpoints")
        val dir = File(baseDir, "checkpoints").apply { mkdirs() }
        val filename = "${JOURNAL_PREFIX}${hashUid(targetUid)}.json"
        return File(dir, filename)
    }

    @Synchronized
    fun saveJournal(entry: LoanMigrationJournalEntry): Boolean {
        return runCatching {
            val file = journalFile()
            val obj = JSONObject()
                .put("operationId", entry.operationId)
                .put("manifestId", entry.manifestId)
                .put("timestampMs", entry.timestampMs)
                .put("schemaVersion", entry.schemaVersion)
                .put("stage", entry.stage.name)
                .put("sourceScope", entry.sourceScope)
                .put("targetUid", entry.targetUid)

            if (entry.errorMessage != null) {
                obj.put("errorMessage", entry.errorMessage)
            }

            val loansObj = JSONObject()
            entry.perLoanStates.forEach { (loanId, state) ->
                val lObj = JSONObject()
                    .put("loanId", state.loanId)
                    .put("totalMutations", state.totalMutations)
                    .put("confirmedMutations", state.confirmedMutations)
                    .put("status", state.status.name)
                    .put("cloudRevision", state.cloudRevision)
                if (state.errorMessage != null) {
                    lObj.put("errorMessage", state.errorMessage)
                }
                loansObj.put(loanId, lObj)
            }
            obj.put("perLoanStates", loansObj)

            file.writeText(obj.toString())
            file.exists() && file.length() > 0
        }.getOrDefault(false)
    }

    @Synchronized
    fun loadJournal(): LoanMigrationJournalEntry? {
        val file = journalFile()
        if (!file.exists() || file.length() == 0L) return null
        val text = file.readText()
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            runCatching {
                val corruptBackup = File(file.parentFile, "loan_journal_corrupt_${System.currentTimeMillis()}.json")
                file.copyTo(corruptBackup, overwrite = true)
            }
            return null
        }

        val version = obj.optInt("schemaVersion", 1)
        if (version > CURRENT_SCHEMA_VERSION) {
            throw UnsupportedSchemaVersionException("Unsupported loan migration schema: $version. Max: $CURRENT_SCHEMA_VERSION")
        }

        return try {
            val perLoanStates = mutableMapOf<String, PerLoanJournalState>()
            val loansObj = obj.optJSONObject("perLoanStates") ?: JSONObject()
            loansObj.keys().forEach { loanId ->
                val lObj = loansObj.getJSONObject(loanId)
                perLoanStates[loanId] = PerLoanJournalState(
                    loanId = lObj.getString("loanId"),
                    totalMutations = lObj.getInt("totalMutations"),
                    confirmedMutations = lObj.getInt("confirmedMutations"),
                    status = LoanCloudSyncState.valueOf(lObj.getString("status")),
                    cloudRevision = lObj.optLong("cloudRevision", 0L),
                    errorMessage = lObj.optString("errorMessage").takeIf { it.isNotBlank() }
                )
            }

            LoanMigrationJournalEntry(
                operationId = obj.getString("operationId"),
                manifestId = obj.getString("manifestId"),
                timestampMs = obj.getLong("timestampMs"),
                schemaVersion = version,
                stage = LoanMigrationStage.valueOf(obj.getString("stage")),
                sourceScope = obj.getString("sourceScope"),
                targetUid = obj.getString("targetUid"),
                perLoanStates = perLoanStates,
                errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clearJournal(): Boolean {
        val file = journalFile()
        return if (file.exists()) file.delete() else true
    }

    @Synchronized
    fun hasPendingMigration(): Boolean {
        val current = loadJournal() ?: return false
        return current.stage != LoanMigrationStage.COMPLETED && current.stage != LoanMigrationStage.FAILED
    }

    @Synchronized
    fun recordMutationConfirmed(loanId: String, revision: Long): Boolean {
        val current = loadJournal() ?: return false
        val loanState = current.perLoanStates[loanId] ?: return false

        val newConfirmed = loanState.confirmedMutations + 1
        val isLoanFinished = newConfirmed >= loanState.totalMutations
        val newLoanStatus = if (isLoanFinished) LoanCloudSyncState.ACKNOWLEDGED else LoanCloudSyncState.UPLOADING

        val updatedLoanState = loanState.copy(
            confirmedMutations = newConfirmed,
            status = newLoanStatus,
            cloudRevision = revision
        )
        val updatedMap = current.perLoanStates.toMutableMap()
        updatedMap[loanId] = updatedLoanState

        val allLoansFinished = updatedMap.values.all { it.status == LoanCloudSyncState.ACKNOWLEDGED }
        val newStage = if (allLoansFinished) LoanMigrationStage.CLOUD_ACKNOWLEDGED else LoanMigrationStage.UPLOADING

        return saveJournal(
            current.copy(
                stage = newStage,
                perLoanStates = updatedMap
            )
        )
    }

    @Synchronized
    fun markLoanFailed(loanId: String, reason: String): Boolean {
        val current = loadJournal() ?: return false
        val loanState = current.perLoanStates[loanId] ?: return false

        val updatedLoanState = loanState.copy(
            status = LoanCloudSyncState.FAILED,
            errorMessage = reason
        )
        val updatedMap = current.perLoanStates.toMutableMap()
        updatedMap[loanId] = updatedLoanState

        return saveJournal(
            current.copy(
                stage = LoanMigrationStage.FAILED,
                perLoanStates = updatedMap,
                errorMessage = "Loan '$loanId' upload failed: $reason"
            )
        )
    }

    @Synchronized
    fun verifyAndComplete(cloudLoans: List<Loan>): Boolean {
        val current = loadJournal() ?: return false
        if (current.stage != LoanMigrationStage.CLOUD_ACKNOWLEDGED) return false

        val cloudLoansById = cloudLoans.associateBy { it.id }
        val allValid = current.perLoanStates.keys.all { id ->
            val cloudLoan = cloudLoansById[id]
            cloudLoan != null
        }

        if (allValid) {
            saveJournal(current.copy(stage = LoanMigrationStage.VERIFIED))
            saveJournal(current.copy(stage = LoanMigrationStage.COMPLETED))
            clearJournal()
            return true
        }
        return false
    }
}
