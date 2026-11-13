package com.example.casha

import com.example.casha.data.FinanceStore
import com.example.casha.data.MigrationInspector
import com.example.casha.data.MigrationJournalStore
import com.example.casha.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

class MigrationFoundationTest {

    private val today = LocalDate.of(2026, 11, 1)
    private val next = LocalDate.of(2026, 11, 15)

    @Before
    fun setUp() {
        val checkpointDir = File(System.getProperty("java.io.tmpdir", "."), "casha_checkpoints/checkpoints")
        if (checkpointDir.exists()) {
            checkpointDir.listFiles()?.forEach { it.delete() }
        }
    }

    @Test
    fun testWalletIdCollision_conflictingMetadata_remapsIdAndRewritesReferences() {
        val srcWallet = MoneyAccount("acc_custom_1", "BDO Savings", isSavings = true, icon = "Savings")
        val tgtWallet = MoneyAccount("acc_custom_1", "BDO Payroll", isSavings = false, icon = "Wallet")

        val srcCash = CashEntry("cash_1", income = false, amount = 1_000L, date = today, note = "Grocery", channel = "BDO Savings")
        val srcLink = PaymentAccountLink(paymentId = "p1", loanId = "l1", accountId = "acc_custom_1", accountName = "BDO Savings")

        val srcSnapshot = LedgerSnapshot(
            scope = "device", hasProfile = false, profile = null,
            wallets = listOf(srcWallet), cashEntries = listOf(srcCash),
            paymentLinks = mapOf("p1" to srcLink), paydayHistory = emptyList(), defaultSalaryAccountId = null
        )
        val tgtSnapshot = LedgerSnapshot(
            scope = "account_tgt", hasProfile = false, profile = null,
            wallets = listOf(tgtWallet), cashEntries = emptyList(),
            paymentLinks = emptyMap(), paydayHistory = emptyList(), defaultSalaryAccountId = null
        )

        val manifest = MigrationInspector.inspect(srcSnapshot, tgtSnapshot)

        assertTrue("Manifest is valid for migration", manifest.isReady)
        assertTrue("Collision detected", manifest.walletMappingPlan.idCollisions.contains("acc_custom_1"))

        val mapping = manifest.walletMappingPlan.entries.first { it.sourceWalletId == "acc_custom_1" }
        assertEquals(WalletMappingAction.REMAP_COLLISION_ID, mapping.action)
        assertTrue("New ID generated", mapping.resolvedTargetWalletId.startsWith("acc_remap_acc_custom_1_"))
        assertEquals("BDO Savings (Imported)", mapping.resolvedTargetWalletName)

        // Foreign keys in cash entries rewritten to remapped name
        val importedCash = manifest.cashEntriesToImport.first()
        assertEquals("BDO Savings (Imported)", importedCash.channel)

        // Foreign keys in payment links rewritten to remapped ID and name
        val importedLink = manifest.paymentLinksToImport.first()
        assertEquals(mapping.resolvedTargetWalletId, importedLink.accountId)
        assertEquals("BDO Savings (Imported)", importedLink.accountName)
    }

    @Test
    fun testSameNameWallets_differentIds_importsAsDistinctWithoutSilentMerging() {
        val srcWallet = MoneyAccount("acc_dev_bpi", "BPI", isSavings = false, icon = "Wallet")
        val tgtWallet = MoneyAccount("acc_tgt_bpi", "BPI", isSavings = false, icon = "Wallet")

        val srcSnapshot = LedgerSnapshot(
            scope = "device", hasProfile = false, profile = null,
            wallets = listOf(srcWallet), cashEntries = emptyList(),
            paymentLinks = emptyMap(), paydayHistory = emptyList(), defaultSalaryAccountId = null
        )
        val tgtSnapshot = LedgerSnapshot(
            scope = "account_tgt", hasProfile = false, profile = null,
            wallets = listOf(tgtWallet), cashEntries = emptyList(),
            paymentLinks = emptyMap(), paydayHistory = emptyList(), defaultSalaryAccountId = null
        )

        // Case A: Default behavior -> never silently combine accounts, create distinct wallet
        val defaultManifest = MigrationInspector.inspect(srcSnapshot, tgtSnapshot)
        assertTrue(defaultManifest.walletMappingPlan.nameCollisions.contains("BPI"))
        val defaultMapping = defaultManifest.walletMappingPlan.entries.first { it.sourceWalletId == "acc_dev_bpi" }
        assertEquals(WalletMappingAction.CREATE_DISTINCT, defaultMapping.action)
        assertEquals("BPI (Device)", defaultMapping.resolvedTargetWalletName)
        assertEquals("acc_dev_bpi", defaultMapping.resolvedTargetWalletId)

        // Case B: Explicit user-directed choice to map to existing target account
        val userChoices = mapOf("acc_dev_bpi" to WalletMappingAction.MAP_TO_TARGET)
        val userMappedManifest = MigrationInspector.inspect(srcSnapshot, tgtSnapshot, userChoices = userChoices)
        val userMapping = userMappedManifest.walletMappingPlan.entries.first { it.sourceWalletId == "acc_dev_bpi" }
        assertEquals(WalletMappingAction.MAP_TO_TARGET, userMapping.action)
        assertEquals("acc_tgt_bpi", userMapping.resolvedTargetWalletId)
        assertEquals("BPI", userMapping.resolvedTargetWalletName)
    }

    @Test
    fun testAmbiguousLegacyChannel_flagsWarningAndPreservesEntry() {
        val bankWallet = MoneyAccount("acc_bank", "Bank", isSavings = false, icon = "Bank")
        val orphanCash = CashEntry("cash_orphan", income = false, amount = 2_500L, date = today, note = "Old note", channel = "PettyCash_2023")

        val srcSnapshot = LedgerSnapshot(
            scope = "device", hasProfile = false, profile = null,
            wallets = listOf(bankWallet), cashEntries = listOf(orphanCash),
            paymentLinks = emptyMap(), paydayHistory = emptyList(), defaultSalaryAccountId = null
        )
        val tgtSnapshot = LedgerSnapshot(
            scope = "account_tgt", hasProfile = false, profile = null,
            wallets = listOf(bankWallet), cashEntries = emptyList(),
            paymentLinks = emptyMap(), paydayHistory = emptyList(), defaultSalaryAccountId = null
        )

        val manifest = MigrationInspector.inspect(srcSnapshot, tgtSnapshot)
        assertTrue(manifest.isReady)
        assertEquals(1, manifest.validation.ambiguousLegacyChannelsCount)
        assertTrue(manifest.validation.warnings.any { it.contains("PettyCash_2023") })
        // Preserved, not discarded
        assertEquals(1, manifest.cashEntriesToImport.size)
        assertEquals("cash_orphan", manifest.cashEntriesToImport.first().id)
    }

    @Test
    fun testSalaryDepositIdentityPreservation_existingTargetSalary_skipsDuplicate() {
        val salaryId = "salary_cutoff_2026-11-01_acc_bank"
        val srcDeposit = CashEntry(salaryId, income = true, amount = 20_000L, date = today, note = "Salary deposit", channel = "Bank")
        val tgtDeposit = CashEntry(salaryId, income = true, amount = 20_000L, date = today, note = "Salary deposit", channel = "Bank")

        val srcSnapshot = LedgerSnapshot(
            scope = "device", hasProfile = true,
            profile = PaydayProfile(today, next, salary = 20_000L, essentials = 5_000L),
            wallets = listOf(MoneyAccount("acc_bank", "Bank")),
            cashEntries = listOf(srcDeposit), paymentLinks = emptyMap(), paydayHistory = emptyList(), defaultSalaryAccountId = "acc_bank"
        )
        val tgtSnapshot = LedgerSnapshot(
            scope = "account_tgt", hasProfile = true,
            profile = PaydayProfile(today, next, salary = 20_000L, essentials = 5_000L),
            wallets = listOf(MoneyAccount("acc_bank", "Bank")),
            cashEntries = listOf(tgtDeposit), paymentLinks = emptyMap(), paydayHistory = emptyList(), defaultSalaryAccountId = "acc_bank"
        )

        val manifest = MigrationInspector.inspect(srcSnapshot, tgtSnapshot)
        assertTrue(manifest.isReady)
        assertEquals(1, manifest.cashEntriesToSkip.size)
        assertTrue(manifest.cashEntriesToImport.isEmpty())

        // Sub-case: Target already has a salary deposit for that date under a DIFFERENT ID
        val tgtDifferentDeposit = CashEntry("salary_cutoff_2026-11-01_acc_other", income = true, amount = 20_000L, date = today, note = "Account salary", channel = "Bank")
        val tgtSnapshotDifferent = tgtSnapshot.copy(cashEntries = listOf(tgtDifferentDeposit))

        val manifestDiff = MigrationInspector.inspect(srcSnapshot, tgtSnapshotDifferent)
        assertTrue(manifestDiff.isReady)
        assertEquals(1, manifestDiff.validation.duplicateSalaryDepositsCount)
        assertTrue(manifestDiff.validation.warnings.any { it.contains("skipping source deposit") })
        assertTrue(manifestDiff.cashEntriesToImport.isEmpty())
        assertEquals(1, manifestDiff.cashEntriesToSkip.size)
    }

    @Test
    fun testDuplicateCashEntries_conflictingContent_failsValidation() {
        val srcEntry = CashEntry("entry_shared", income = false, amount = 5_000L, date = today, note = "Dinner", channel = "Bank")
        val tgtEntry = CashEntry("entry_shared", income = true, amount = 10_000L, date = today, note = "Bonus", channel = "Bank")

        val srcSnapshot = LedgerSnapshot(
            scope = "device", hasProfile = false, profile = null,
            wallets = listOf(MoneyAccount("acc_bank", "Bank")),
            cashEntries = listOf(srcEntry), paymentLinks = emptyMap(), paydayHistory = emptyList(), defaultSalaryAccountId = null
        )
        val tgtSnapshot = LedgerSnapshot(
            scope = "account_tgt", hasProfile = false, profile = null,
            wallets = listOf(MoneyAccount("acc_bank", "Bank")),
            cashEntries = listOf(tgtEntry), paymentLinks = emptyMap(), paydayHistory = emptyList(), defaultSalaryAccountId = null
        )

        val manifest = MigrationInspector.inspect(srcSnapshot, tgtSnapshot)
        assertFalse("Migration must fail on conflicting cash IDs", manifest.isReady)
        assertEquals(1, manifest.validation.conflictingCashEntriesCount)
        assertTrue(manifest.validation.errors.any { it.contains("Conflicting cash entry ID collision") })
    }

    @Test
    fun testOrphanedPaymentWalletLinks_detectsOrphansAndEmitsWarnings() {
        val activeLoan = Loan(id = "l_active", party = "Bank", mode = LoanMode.I_OWE, total = 10_000L, count = 2, firstDue = today, frequency = Frequency.MONTHLY)
            .pay(0, 5_000L, "p_active", today)

        val orphanLink = PaymentAccountLink(paymentId = "p_deleted", loanId = "l_deleted", accountId = "acc_bank", accountName = "Bank")
        val activeLink = PaymentAccountLink(paymentId = "p_active", loanId = "l_active", accountId = "acc_bank", accountName = "Bank")

        val srcSnapshot = LedgerSnapshot(
            scope = "device", hasProfile = false, profile = null,
            wallets = listOf(MoneyAccount("acc_bank", "Bank")),
            cashEntries = emptyList(),
            paymentLinks = mapOf("p_deleted" to orphanLink, "p_active" to activeLink),
            paydayHistory = emptyList(), defaultSalaryAccountId = null
        )
        val tgtSnapshot = LedgerSnapshot(
            scope = "account_tgt", hasProfile = false, profile = null,
            wallets = listOf(MoneyAccount("acc_bank", "Bank")),
            cashEntries = emptyList(), paymentLinks = emptyMap(), paydayHistory = emptyList(), defaultSalaryAccountId = null
        )

        val manifest = MigrationInspector.inspect(srcSnapshot, tgtSnapshot, loans = listOf(activeLoan))
        assertTrue(manifest.isReady)
        assertEquals(1, manifest.validation.orphanedPaymentLinksCount)
        assertTrue(manifest.validation.warnings.any { it.contains("p_deleted") })
        assertEquals(2, manifest.paymentLinksToImport.size)
    }

    @Test
    fun testMigrationManifest_deterministicAcrossRepeatedInvocations() {
        val srcWallet = MoneyAccount("acc_w1", "Wallet 1")
        val srcCash = CashEntry("c1", income = true, amount = 1_000L, date = today, note = "Test", channel = "Wallet 1")
        val srcLink = PaymentAccountLink(paymentId = "p1", loanId = "l1", accountId = "acc_w1", accountName = "Wallet 1")

        val srcSnapshot = LedgerSnapshot(
            scope = "device", hasProfile = false, profile = null,
            wallets = listOf(srcWallet), cashEntries = listOf(srcCash),
            paymentLinks = mapOf("p1" to srcLink), paydayHistory = emptyList(), defaultSalaryAccountId = null
        )
        val tgtSnapshot = LedgerSnapshot(
            scope = "account_tgt", hasProfile = false, profile = null,
            wallets = emptyList(), cashEntries = emptyList(),
            paymentLinks = emptyMap(), paydayHistory = emptyList(), defaultSalaryAccountId = null
        )

        val manifest1 = MigrationInspector.inspect(srcSnapshot, tgtSnapshot)
        val manifest2 = MigrationInspector.inspect(srcSnapshot, tgtSnapshot)
        val manifest3 = MigrationInspector.inspect(srcSnapshot, tgtSnapshot)

        assertEquals(manifest1.manifestId, manifest2.manifestId)
        assertEquals(manifest2.manifestId, manifest3.manifestId)
        assertEquals(manifest1.walletMappingPlan.entries, manifest2.walletMappingPlan.entries)
        assertEquals(manifest1.cashEntriesToImport, manifest2.cashEntriesToImport)
        assertEquals(manifest1.paymentLinksToImport, manifest2.paymentLinksToImport)
    }

    @Test
    fun testRepeatedDryRunOperations_readOnly_leavesSourceAndTargetUntouched() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "dry_src", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "dry_tgt", preferences = fakeTgtPrefs)

        srcStore.saveProfile(PaydayProfile(today, next, salary = 20_000L, essentials = 5_000L))
        srcStore.addCash(CashEntry("ce_dry", income = true, amount = 5_000L, date = today, note = "Dry run", channel = "Bank"))

        val srcPreState = HashMap(fakeSrcPrefs.data)
        val tgtPreState = HashMap(fakeTgtPrefs.data)

        // Run dry-run inspection 3 times
        for (i in 1..3) {
            val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())
            assertTrue(manifest.isReady)
        }

        // Assert strictly untouched
        assertEquals(srcPreState, fakeSrcPrefs.data)
        assertEquals(tgtPreState, fakeTgtPrefs.data)
    }

    @Test
    fun testCrashRecovery_beforeMigrationWrites_recoversPreTargetStateCleanly() {
        val fakeTgtPrefs = FakeSharedPreferences()
        val tgtStore = FinanceStore(context = null, scope = "crash_tgt", preferences = fakeTgtPrefs)
        tgtStore.saveProfile(PaydayProfile(today, next, salary = 30_000L, essentials = 10_000L))

        val preTargetSnapshot = tgtStore.exportPreferencesSnapshot()
        val journalStore = MigrationJournalStore(context = null)

        val journalEntry = MigrationJournalEntry(
            migrationId = "mig_crash_1",
            timestampMs = System.currentTimeMillis(),
            schemaVersion = 1,
            stage = MigrationStage.READY_TO_COMMIT, // Crashed before commit completed
            sourceScope = "device",
            targetScope = "crash_tgt",
            manifestId = "manifest_test",
            preTargetSnapshotJson = preTargetSnapshot
        )
        assertTrue("Saved journal entry", journalStore.saveJournal(journalEntry))

        // Simulate dirty writes before crash
        fakeTgtPrefs.data["corrupted_key"] = "corrupted_val"
        fakeTgtPrefs.data["profile"] = "{\"salary\":999999}"

        // Recovery on app launch
        val recovered = journalStore.recoverIfInterrupted(tgtStore)
        assertTrue("Recovered cleanly", recovered)
        assertEquals(30_000L, tgtStore.profile().salary)
        assertFalse(fakeTgtPrefs.data.containsKey("corrupted_key"))
        assertNull("Journal cleared after rollback", journalStore.loadJournal())
    }

    @Test
    fun testCorruptedOrIncompleteMigrationCheckpoints_handledDefensivelyWithoutCrashing() {
        val journalStore = MigrationJournalStore(context = null)
        val checkpointFile = File(System.getProperty("java.io.tmpdir", "."), "casha_checkpoints/checkpoints/migration_journal.json")
        checkpointFile.parentFile?.mkdirs()

        // Test A: Incomplete / malformed JSON
        checkpointFile.writeText("{\"migrationId\": \"test\", \"schemaVersion\": ")
        val entryA = journalStore.loadJournal()
        assertNull("Malformed JSON returns null without throwing", entryA)

        // Test B: Empty file
        checkpointFile.writeText("")
        val entryB = journalStore.loadJournal()
        assertNull("Empty file returns null", entryB)

        // Recovery should not throw
        val recovered = journalStore.recoverIfInterrupted(null)
        assertFalse("Cannot recover from missing/corrupted file", recovered)
    }

    @Test
    fun testUnsupportedMigrationSchemaVersions_rejectsFutureVersionSafely() {
        val journalStore = MigrationJournalStore(context = null)
        val checkpointFile = File(System.getProperty("java.io.tmpdir", "."), "casha_checkpoints/checkpoints/migration_journal.json")
        checkpointFile.parentFile?.mkdirs()

        val futureJson = JSONObject()
            .put("migrationId", "mig_future")
            .put("timestampMs", System.currentTimeMillis())
            .put("schemaVersion", 999) // Future unsupported schema version!
            .put("stage", "PREPARING")
            .put("sourceScope", "device")
            .put("targetScope", "tgt")
            .put("manifestId", "man_1")
            .toString()

        checkpointFile.writeText(futureJson)

        try {
            journalStore.loadJournal()
            fail("Expected UnsupportedSchemaVersionException")
        } catch (e: UnsupportedSchemaVersionException) {
            assertTrue(e.message?.contains("Unsupported migration schema version: 999") == true)
        }
    }
}
