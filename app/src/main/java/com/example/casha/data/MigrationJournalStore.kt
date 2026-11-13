package com.example.casha.data

import android.content.Context
import com.example.casha.domain.Loan
import com.example.casha.domain.MigrationJournalEntry
import com.example.casha.domain.MigrationStage
import com.example.casha.domain.PaydayPlanner
import com.example.casha.domain.UnsupportedSchemaVersionException
import org.json.JSONObject
import java.io.File

class MigrationJournalStore(val context: Context? = null) {

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val JOURNAL_FILE_NAME = "migration_journal.json"
    }

    private fun journalFile(): File {
        val baseDir = context?.filesDir ?: File(System.getProperty("java.io.tmpdir", "."), "casha_checkpoints")
        val dir = File(baseDir, "checkpoints").apply { mkdirs() }
        return File(dir, JOURNAL_FILE_NAME)
    }

    fun saveJournal(entry: MigrationJournalEntry): Boolean {
        return runCatching {
            val file = journalFile()
            val obj = JSONObject()
                .put("migrationId", entry.migrationId)
                .put("timestampMs", entry.timestampMs)
                .put("schemaVersion", entry.schemaVersion)
                .put("stage", entry.stage.name)
                .put("sourceScope", entry.sourceScope)
                .put("targetScope", entry.targetScope)
                .put("manifestId", entry.manifestId)
            if (entry.preTargetSnapshotJson != null) {
                obj.put("preTargetSnapshotJson", entry.preTargetSnapshotJson)
            }
            if (entry.errorMessage != null) {
                obj.put("errorMessage", entry.errorMessage)
            }
            if (entry.expectedTargetFingerprint != null) {
                obj.put("expectedTargetFingerprint", entry.expectedTargetFingerprint)
            }
            if (entry.expectedAccountsCount != null) {
                obj.put("expectedAccountsCount", entry.expectedAccountsCount)
            }
            if (entry.expectedCashCount != null) {
                obj.put("expectedCashCount", entry.expectedCashCount)
            }
            if (entry.expectedBalances != null) {
                val bObj = JSONObject()
                entry.expectedBalances.forEach { (k, v) -> bObj.put(k, v) }
                obj.put("expectedBalances", bObj)
            }
            file.writeText(obj.toString())
            file.exists() && file.length() > 0
        }.getOrDefault(false)
    }

    fun loadJournal(): MigrationJournalEntry? {
        val file = journalFile()
        if (!file.exists() || file.length() == 0L) return null
        val text = file.readText()
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            // Defensively preserve corrupted journal for diagnosis and prevent unsafe blind retries
            runCatching {
                val corruptBackup = File(file.parentFile, "migration_journal_corrupt_${System.currentTimeMillis()}.json")
                file.copyTo(corruptBackup, overwrite = true)
            }
            return null
        }

        val version = obj.optInt("schemaVersion", 1)
        if (version > CURRENT_SCHEMA_VERSION) {
            throw UnsupportedSchemaVersionException("Unsupported migration schema version: $version. Maximum supported version is $CURRENT_SCHEMA_VERSION.")
        }

        return try {
            MigrationJournalEntry(
                migrationId = obj.getString("migrationId"),
                timestampMs = obj.getLong("timestampMs"),
                schemaVersion = version,
                stage = MigrationStage.valueOf(obj.getString("stage")),
                sourceScope = obj.getString("sourceScope"),
                targetScope = obj.getString("targetScope"),
                manifestId = obj.getString("manifestId"),
                preTargetSnapshotJson = obj.optString("preTargetSnapshotJson").takeIf { it.isNotBlank() },
                errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() },
                expectedTargetFingerprint = obj.optString("expectedTargetFingerprint").takeIf { it.isNotBlank() },
                expectedAccountsCount = if (obj.has("expectedAccountsCount")) obj.getInt("expectedAccountsCount") else null,
                expectedCashCount = if (obj.has("expectedCashCount")) obj.getInt("expectedCashCount") else null,
                expectedBalances = if (obj.has("expectedBalances")) {
                    val bObj = obj.getJSONObject("expectedBalances")
                    val map = mutableMapOf<String, Long>()
                    bObj.keys().forEach { k -> map[k] = bObj.getLong(k) }
                    map
                } else null
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearJournal(): Boolean {
        return runCatching {
            val file = journalFile()
            if (file.exists()) file.delete() else true
        }.getOrDefault(false)
    }

    fun hasUnresolvedJournal(targetScope: String? = null): Boolean {
        val journal = loadJournal() ?: return false
        return if (targetScope != null) journal.targetScope == targetScope else true
    }

    fun recoverIfInterrupted(targetStore: FinanceStore? = null): Boolean {
        return runCatching {
            val journal = loadJournal() ?: return false
            when (journal.stage) {
                MigrationStage.PREPARING,
                MigrationStage.VALIDATING,
                MigrationStage.READY_TO_COMMIT,
                MigrationStage.FAILED -> {
                    // Crashed before local writes committed: rollback target if snapshot exists
                    var rolledBack = true
                    if (targetStore != null && journal.preTargetSnapshotJson != null) {
                        rolledBack = targetStore.restorePreferencesSnapshot(journal.preTargetSnapshotJson)
                    }
                    if (rolledBack) {
                        clearJournal()
                    }
                    rolledBack
                }
                MigrationStage.LOCAL_COMMITTED -> {
                    // Local commit was verified and must NOT be rolled back.
                    // Do not automatically mark the migration successful.
                    // Resume financial balance and reference validation against the committed target.
                    if (targetStore != null && targetStore.scope == journal.targetScope) {
                        val validationPassed = runCatching {
                            val accounts = targetStore.allMoneyAccounts()
                            val entries = targetStore.cashEntries()
                            val links = targetStore.allPaymentLinks()
                            val profile = targetStore.profile()
                            val commitMarkerVal = targetStore.preferences.getString("migrated_from_scope", null)
                            val hasCommitMarker = commitMarkerVal != null && commitMarkerVal == journal.sourceScope
                            val accountsMatch = journal.expectedAccountsCount == null || accounts.size >= journal.expectedAccountsCount
                            val cashMatch = journal.expectedCashCount == null || entries.size >= journal.expectedCashCount
                            val fingerprintMatch = journal.expectedTargetFingerprint == null || targetStore.inspectLedger().computeFingerprint() == journal.expectedTargetFingerprint
                            val balances = PaydayPlanner.accountBalances(
                                accounts = accounts,
                                entries = entries,
                                profile = profile,
                                defaultSalaryAccountId = targetStore.defaultSalaryAccountId(),
                                paymentLinks = links,
                                loans = emptyList<Loan>()
                            )
                            val balancesComplete = accounts.isNotEmpty() && balances.keys.containsAll(accounts.map { it.id })
                            val balancesMatch = journal.expectedBalances == null || balances == journal.expectedBalances
                            hasCommitMarker && accountsMatch && cashMatch && fingerprintMatch && balancesMatch && balancesComplete
                        }.getOrDefault(false)

                        if (validationPassed) {
                            saveJournal(journal.copy(stage = MigrationStage.BALANCES_VERIFIED))
                            saveJournal(journal.copy(stage = MigrationStage.COMPLETED))
                            clearJournal()
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
                MigrationStage.BALANCES_VERIFIED,
                MigrationStage.COMPLETED,
                MigrationStage.ROLLED_BACK -> {
                    clearJournal()
                    true
                }
                MigrationStage.QUEUED_FOR_CLOUD -> {
                    // Cloud queue will resume via CloudLoanRepository.
                    false
                }
            }
        }.getOrDefault(false)
    }
}
