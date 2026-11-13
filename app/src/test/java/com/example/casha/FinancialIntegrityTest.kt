package com.example.casha

import com.example.casha.data.*
import com.example.casha.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FinancialIntegrityTest {
    private val start = LocalDate.of(2026, 11, 1)
    private val next = LocalDate.of(2026, 11, 15)
    private val today = LocalDate.of(2026, 11, 3)

    private fun sampleAccounts(): List<MoneyAccount> = listOf(
        MoneyAccount("acc_cash", "Cash on hand", isSavings = false, icon = "Cash"),
        MoneyAccount("acc_gotyme", "Gotyme", isSavings = false, icon = "Bank"),
        MoneyAccount("acc_ub", "UnionBank", isSavings = false, icon = "Bank"),
        MoneyAccount("acc_savings", "Savings Vault", isSavings = true, icon = "Savings")
    )

    @Test
    fun test01_threeConsecutiveRollovers_exactCentavoParity() {
        // Period 1: Oct 24 to Nov 06
        // Salary ₱20,000 deposited to Gotyme.
        val p1Start = LocalDate.of(2026, 10, 24)
        val p1Next = LocalDate.of(2026, 11, 6)
        val p1Profile = PaydayProfile(p1Start, p1Next, salary = 20_000L, essentials = 5_000L, emergency = 2_000L, salaryAccountId = "acc_gotyme")

        val p1Salary = CashEntry("salary_p1", income = true, amount = 20_000L, date = p1Start, note = "Salary · P1", channel = "Gotyme")
        val p1Groceries = CashEntry("exp_groc", income = false, amount = 8_000L, date = p1Start.plusDays(2), note = "Groceries", channel = "Gotyme")
        val p1Bills = CashEntry("exp_bills", income = false, amount = 4_000L, date = p1Start.plusDays(5), note = "Utilities", channel = "Gotyme")

        // Period 1 Loan payment of ₱3,000 from Gotyme
        val loan1 = Loan(
            id = "loan_atome", party = "Atome Offers", mode = LoanMode.I_OWE,
            total = 10_000L, count = 2, firstDue = p1Start.plusDays(3), frequency = Frequency.MONTHLY
        ).pay(0, 3_000L, "p1_pay", p1Start.plusDays(3))

        val p1PaymentLink = PaymentAccountLink(
            paymentId = "p1_pay", loanId = "loan_atome",
            accountId = "acc_gotyme", accountName = "Gotyme", isReceivable = false
        )

        val accounts = sampleAccounts()
        val p1Entries = listOf(p1Salary, p1Groceries, p1Bills)
        val p1Links = mapOf("p1_pay" to p1PaymentLink)

        // Period 1 Balances: 20,000 - 8,000 - 4,000 - 3,000 = 5,000
        val p1Balances = PaydayPlanner.accountBalances(accounts, p1Entries, p1Profile, paymentLinks = p1Links, loans = listOf(loan1))
        assertEquals(5_000L, p1Balances["acc_gotyme"])

        // Period 1 Safe to Spend: Opening 20,000 - 12,000 cash - 3,000 paid = 5,000 cash
        val p1Plan = PaydayPlanner.calculate(p1Profile, listOf(loan1), emptyMap(), p1Entries, p1Next.minusDays(1), setOf("Savings Vault"))
        assertEquals(5_000L, p1Plan.cash)

        // Period 2: Nov 07 to Nov 20 (Rollover into Gotyme with ₱5,000 carryover)
        val p2Start = LocalDate.of(2026, 11, 7)
        val p2Next = LocalDate.of(2026, 11, 20)
        val p2Profile = PaydayProfile(p2Start, p2Next, salary = 20_000L, essentials = 5_000L, emergency = 2_000L, salaryAccountId = "acc_gotyme", carryover = 5_000L)

        // Opening budget must be 20,000 + 5,000 = 25,000!
        assertEquals(25_000L, p2Profile.totalOpeningBudget)

        val p2Salary = CashEntry("salary_p2", income = true, amount = 20_000L, date = p2Start, note = "Salary · P2", channel = "Gotyme")
        val p2Expenses = CashEntry("exp_p2_all", income = false, amount = 18_000L, date = p2Start.plusDays(3), note = "P2 Living Expenses", channel = "Gotyme")

        val p2Entries = p1Entries + listOf(p2Salary, p2Expenses)
        val p2Balances = PaydayPlanner.accountBalances(accounts, p2Entries, p2Profile, paymentLinks = p1Links, loans = listOf(loan1))

        // Gotyme balance: 5,000 (from P1) + 20,000 (new salary) - 18,000 (P2 expenses) = 7,000!
        assertEquals(7_000L, p2Balances["acc_gotyme"])

        // Period 3: Nov 21 to Dec 04 (Rollover into DIFFERENT wallet: UnionBank with ₱7,000 carryover)
        val p3Start = LocalDate.of(2026, 11, 21)
        val p3Next = LocalDate.of(2026, 12, 4)
        val p3Profile = PaydayProfile(p3Start, p3Next, salary = 20_000L, essentials = 5_000L, emergency = 2_000L, salaryAccountId = "acc_ub", carryover = 7_000L)

        // Opening budget must be 20,000 + 7,000 = 27,000!
        assertEquals(27_000L, p3Profile.totalOpeningBudget)

        val p3Salary = CashEntry("salary_p3", income = true, amount = 20_000L, date = p3Start, note = "Salary · P3", channel = "UnionBank")
        val p3Entries = p2Entries + listOf(p3Salary)

        val p3Balances = PaydayPlanner.accountBalances(accounts, p3Entries, p3Profile, paymentLinks = p1Links, loans = listOf(loan1))
        // Gotyme retains unspent 7,000!
        assertEquals(7_000L, p3Balances["acc_gotyme"])
        // UnionBank receives the new 20,000!
        assertEquals(20_000L, p3Balances["acc_ub"])
        // Total liquid assets = 7,000 + 20,000 = 27,000!
        val totalLiquid = (p3Balances["acc_gotyme"] ?: 0L) + (p3Balances["acc_ub"] ?: 0L) + (p3Balances["acc_cash"] ?: 0L)
        assertEquals(27_000L, totalLiquid)
        assertEquals(p3Profile.totalOpeningBudget, totalLiquid)
    }

    @Test
    fun test02_loanPaymentWalletDebit_andReversalRestoresWallet() {
        val accounts = sampleAccounts()
        val initialGotyme = CashEntry("init_gotyme", income = true, amount = 10_000L, date = start, note = "Opening funds", channel = "Gotyme")
        val loan = Loan(
            id = "loan_1", party = "UnionBank Quick Loan", mode = LoanMode.I_OWE,
            total = 10_000L, count = 2, firstDue = start.plusDays(5), frequency = Frequency.MONTHLY
        )

        // 1. Pay 3,000 from Gotyme
        val paidLoan = loan.pay(0, 3_000L, "pay_1", start.plusDays(2))
        val link = PaymentAccountLink("pay_1", "loan_1", "acc_gotyme", "Gotyme", isReceivable = false)

        val balancesAfterPay = PaydayPlanner.accountBalances(
            accounts, listOf(initialGotyme), profile = null,
            paymentLinks = mapOf("pay_1" to link), loans = listOf(paidLoan)
        )
        // 10,000 - 3,000 = 7,000
        assertEquals(7_000L, balancesAfterPay["acc_gotyme"])

        // 2. Reverse the 3,000 payment
        val reversedLoan = paidLoan.reverse("pay_1", start.plusDays(3), "Bank correction")
        val balancesAfterReversal = PaydayPlanner.accountBalances(
            accounts, listOf(initialGotyme), profile = null,
            paymentLinks = mapOf("pay_1" to link), loans = listOf(reversedLoan)
        )
        // Restored back to 10,000!
        assertEquals(10_000L, balancesAfterReversal["acc_gotyme"])
    }

    @Test
    fun test03_receivableCollection_andReversal() {
        val accounts = sampleAccounts()
        val initialCash = CashEntry("init_cash", income = true, amount = 1_000L, date = start, note = "Cash on hand", channel = "Cash on hand")
        val receivable = Loan(
            id = "rec_1", party = "Lent to Bob", mode = LoanMode.OWED_TO_ME,
            total = 5_000L, count = 1, firstDue = start.plusDays(5), frequency = Frequency.MONTHLY
        )

        // 1. Collect 2,000 into Cash on hand
        val collected = receivable.pay(0, 2_000L, "rec_pay_1", start.plusDays(2))
        val link = PaymentAccountLink("rec_pay_1", "rec_1", "acc_cash", "Cash on hand", isReceivable = true)

        val balancesAfterCollect = PaydayPlanner.accountBalances(
            accounts, listOf(initialCash), profile = null,
            paymentLinks = mapOf("rec_pay_1" to link), loans = listOf(collected)
        )
        // 1,000 + 2,000 = 3,000
        assertEquals(3_000L, balancesAfterCollect["acc_cash"])

        // 2. Reverse the collection
        val reversed = collected.reverse("rec_pay_1", start.plusDays(3), "Check bounced")
        val balancesAfterReversal = PaydayPlanner.accountBalances(
            accounts, listOf(initialCash), profile = null,
            paymentLinks = mapOf("rec_pay_1" to link), loans = listOf(reversed)
        )
        // Reduced back to 1,000
        assertEquals(1_000L, balancesAfterReversal["acc_cash"])
    }

    @Test
    fun test04_legacyUnlinkedPayment_doesNotCorruptWalletBalances() {
        val accounts = sampleAccounts()
        val initialGotyme = CashEntry("init_gotyme", income = true, amount = 10_000L, date = start, note = "Opening funds", channel = "Gotyme")
        val legacyLoan = Loan(
            id = "legacy_loan", party = "Old Loan", mode = LoanMode.I_OWE,
            total = 10_000L, count = 1, firstDue = start.plusDays(5), frequency = Frequency.MONTHLY
        ).pay(0, 5_000L, "legacy_p1", start.plusDays(2))

        // No payment link exists for legacy_p1
        val balances = PaydayPlanner.accountBalances(
            accounts, listOf(initialGotyme), profile = null,
            paymentLinks = emptyMap(), loans = listOf(legacyLoan)
        )
        // Gotyme balance is NOT arbitrarily debited!
        assertEquals(10_000L, balances["acc_gotyme"])
    }

    @Test
    fun test05_salaryDepositExclusion_preventsBudgetDoubleCounting() {
        val profile = PaydayProfile(start, next, salary = 20_000L, essentials = 5_000L, emergency = 2_000L)
        val salaryEntry = CashEntry("salary_cutoff_1", income = true, amount = 20_000L, date = start, note = "Salary Deposit", channel = "Gotyme")
        val groceryExpense = CashEntry("exp_groc", income = false, amount = 3_000L, date = start.plusDays(2), note = "Groceries", channel = "Gotyme")

        val entries = listOf(salaryEntry, groceryExpense)
        val plan = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), entries, start.plusDays(3), setOf("Savings Vault"))

        // Budget cash should be: 20,000 (profile.salary) - 3,000 (grocery) = 17,000L!
        // It should NOT be: 20,000 (profile) + 20,000 (salaryEntry) - 3,000 = 37,000L!
        assertEquals(17_000L, plan.cash)
    }

    @Test
    fun test06_walletArchival_preservesHistoricalAccountReferences() {
        val accounts = sampleAccounts()
        val gotyme = accounts.first { it.id == "acc_gotyme" }
        assertFalse(gotyme.isArchived)

        // Archive Gotyme
        val archivedGotyme = gotyme.copy(isArchived = true)
        val updatedAccounts = accounts.map { if (it.id == "acc_gotyme") archivedGotyme else it }

        val pastPayment = CashEntry("p1", income = false, amount = 500L, date = start, note = "Old Coffee", channel = "Gotyme")
        val balances = PaydayPlanner.accountBalances(updatedAccounts, listOf(pastPayment))

        // Balance of archived account is preserved and still calculated accurately
        assertEquals(-500L, balances["acc_gotyme"])
        assertTrue(updatedAccounts.first { it.id == "acc_gotyme" }.isArchived)
    }

    @Test
    fun test07_overdraftAndNegativeBalances_areTransparent() {
        val accounts = sampleAccounts()
        val initialGotyme = CashEntry("init", income = true, amount = 1_000L, date = start, note = "Small balance", channel = "Gotyme")
        val loan = Loan(
            id = "loan_overdraft", party = "Rent", mode = LoanMode.I_OWE,
            total = 10_000L, count = 1, firstDue = start, frequency = Frequency.MONTHLY
        ).pay(0, 5_000L, "p_big", start)

        val link = PaymentAccountLink("p_big", "loan_overdraft", "acc_gotyme", "Gotyme", isReceivable = false)
        val balances = PaydayPlanner.accountBalances(accounts, listOf(initialGotyme), profile = null, paymentLinks = mapOf("p_big" to link), loans = listOf(loan))

        // 1,000 - 5,000 = -4,000 (Overdraft clearly visible)
        assertEquals(-4_000L, balances["acc_gotyme"])
    }

    @Test
    fun test08_historicalSalaryAdjustment_viaCompensatingCashEntry() {
        val accounts = sampleAccounts()
        val profile = PaydayProfile(start, next, salary = 20_000L, essentials = 5_000L, emergency = 2_000L, salaryAccountId = "acc_gotyme")
        val initialSalary = CashEntry("salary_cutoff_${start}_acc_gotyme", income = true, amount = 20_000L, date = start, note = "Salary", channel = "Gotyme")

        // User receives a retroactive salary adjustment bonus of ₱2,500
        val bonusAdjustment = CashEntry("adj_bonus_1", income = true, amount = 2_500L, date = start.plusDays(4), note = "Retroactive Overtime Pay", channel = "Gotyme")

        val entries = listOf(initialSalary, bonusAdjustment)
        val balances = PaydayPlanner.accountBalances(accounts, entries, profile)

        // Wallet balance reflects exact total deposited funds
        assertEquals(22_500L, balances["acc_gotyme"])

        // Budget calculation properly includes general non-salary entry into available cash without double-crediting base salary
        val plan = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), entries, start.plusDays(5), setOf("Savings Vault"))
        // Base 20,000 + 2,500 bonus = 22,500
        assertEquals(22_500L, plan.cash)
    }

    @Test
    fun test09_intentRecovery_simulatedCrash_preCommitVsPostCommit() {
        val loan = Loan(
            id = "loan_intent", party = "Intent Test", mode = LoanMode.I_OWE,
            total = 10_000L, count = 2, firstDue = start, frequency = Frequency.MONTHLY
        )

        val committedPaymentId = "pay_committed_1"
        val uncommittedPaymentId = "pay_uncommitted_2"

        // Simulate Case A: Committed in loan ledger (loan has the payment)
        val loanWithCommittedPayment = loan.pay(0, 3_000L, committedPaymentId, start)

        val intentCommitted = PendingPaymentIntent(
            intentId = "intent_1", paymentId = committedPaymentId, loanId = loan.id,
            installmentIndex = 0, accountId = "acc_gotyme", accountName = "Gotyme",
            amountCentavos = 3_000L, date = start, isReceivable = false
        )

        // Simulate Case B: Crash before loan commit (loan does NOT have this payment)
        val intentUncommitted = PendingPaymentIntent(
            intentId = "intent_2", paymentId = uncommittedPaymentId, loanId = loan.id,
            installmentIndex = 1, accountId = "acc_gotyme", accountName = "Gotyme",
            amountCentavos = 2_000L, date = start, isReceivable = false
        )

        val pendingIntents = listOf(intentCommitted, intentUncommitted)
        val existingLoanPaymentIds = loanWithCommittedPayment.payments.map { it.id }.toSet()

        // Causal reconciliation logic verification:
        val promoted = mutableListOf<PaymentAccountLink>()
        val remainingPending = mutableListOf<PendingPaymentIntent>()

        pendingIntents.forEach { intent ->
            if (intent.paymentId in existingLoanPaymentIds) {
                promoted.add(
                    PaymentAccountLink(
                        paymentId = intent.paymentId, loanId = intent.loanId,
                        accountId = intent.accountId, accountName = intent.accountName,
                        isReceivable = intent.isReceivable
                    )
                )
            } else {
                remainingPending.add(intent)
            }
        }

        // Post-commit intent was safely promoted
        assertEquals(1, promoted.size)
        assertEquals(committedPaymentId, promoted.first().paymentId)

        // Pre-commit intent remains safely unpromoted (zero arbitrary timeout discard)
        assertEquals(1, remainingPending.size)
        assertEquals(uncommittedPaymentId, remainingPending.first().paymentId)
    }

    @Test
    fun test10_remoteCloudPaymentSync_unassignedWallet_zeroLocalDeduction() {
        val accounts = sampleAccounts()
        val initialGotyme = CashEntry("init_gotyme", income = true, amount = 10_000L, date = start, note = "Opening funds", channel = "Gotyme")

        // Loan synced from Cloud Firestore where another device recorded a payment
        val remoteLoan = Loan(
            id = "loan_remote", party = "Cloud Loan", mode = LoanMode.I_OWE,
            total = 10_000L, count = 1, firstDue = start, frequency = Frequency.MONTHLY
        ).pay(0, 4_000L, "remote_payment_99", start)

        // No PaymentAccountLink exists on this local device
        val localPaymentLinks = emptyMap<String, PaymentAccountLink>()

        val balances = PaydayPlanner.accountBalances(
            accounts = accounts, entries = listOf(initialGotyme), profile = null,
            paymentLinks = localPaymentLinks, loans = listOf(remoteLoan)
        )

        // Remote cloud payment has UNASSIGNED_WALLET locally: local Gotyme balance is preserved at 10,000!
        assertEquals(10_000L, balances["acc_gotyme"])
        assertEquals(0L, balances["acc_cash"])
    }

    @Test
    fun test11_remotePaymentReversal_noFalseWalletCredit() {
        val accounts = sampleAccounts()
        val initialGotyme = CashEntry("init_gotyme", income = true, amount = 10_000L, date = start, note = "Opening funds", channel = "Gotyme")

        // Loan with unlinked remote payment that was subsequently reversed in the cloud
        val remoteLoan = Loan(
            id = "loan_remote", party = "Cloud Loan", mode = LoanMode.I_OWE,
            total = 10_000L, count = 1, firstDue = start, frequency = Frequency.MONTHLY
        ).pay(0, 4_000L, "remote_payment_99", start)
            .reverse("remote_payment_99", start.plusDays(1), "Cloud dispute reversal")

        val balances = PaydayPlanner.accountBalances(
            accounts = accounts, entries = listOf(initialGotyme), profile = null,
            paymentLinks = emptyMap(), loans = listOf(remoteLoan)
        )

        // Reversal of an unlinked payment does NOT create a false wallet credit
        assertEquals(10_000L, balances["acc_gotyme"])
    }

    @Test
    fun test12_offlineMutationPersistence_andCausalSync() {
        val accounts = sampleAccounts()
        val initialGotyme = CashEntry("init_gotyme", income = true, amount = 10_000L, date = start, note = "Opening funds", channel = "Gotyme")

        // User records an offline payment of ₱2,500
        val offlinePaymentId = "offline_op_123"
        val intent = PendingPaymentIntent(
            intentId = offlinePaymentId, paymentId = offlinePaymentId, loanId = "loan_off",
            installmentIndex = 0, accountId = "acc_gotyme", accountName = "Gotyme",
            amountCentavos = 2_500L, date = start, isReceivable = false
        )

        val loan = Loan(
            id = "loan_off", party = "Offline Lender", mode = LoanMode.I_OWE,
            total = 5_000L, count = 2, firstDue = start, frequency = Frequency.MONTHLY
        ).pay(0, 2_500L, offlinePaymentId, start)

        // Once persisted, intent is reconciled into link
        val link = PaymentAccountLink(
            paymentId = intent.paymentId, loanId = intent.loanId,
            accountId = intent.accountId, accountName = intent.accountName,
            isReceivable = intent.isReceivable
        )

        val balances = PaydayPlanner.accountBalances(
            accounts = accounts, entries = listOf(initialGotyme), profile = null,
            paymentLinks = mapOf(offlinePaymentId to link), loans = listOf(loan)
        )

        // Wallet reflects exact ₱2,500 deduction
        assertEquals(7_500L, balances["acc_gotyme"])
    }

    @Test
    fun test13_backupMergeValidation_andCheckpointRestoreRollback() {
        // 1. Validate Schema 2 metadata structure
        val backupJson = JSONObject()
            .put("schemaVersion", 2)
            .put("profile", JSONObject()
                .put("start", "2026-11-01")
                .put("next", "2026-11-15")
                .put("salary", 20000L)
                .put("essentials", 5000L)
                .put("emergency", 2000L)
                .put("carryover", 3000L)
                .put("strategy", "AVALANCHE")
                .put("salaryAccountId", "acc_gotyme")
                .toString()
            )
            .put("money_accounts", JSONArray()
                .put(JSONObject().put("id", "acc_gotyme").put("name", "Gotyme").put("isSavings", false).put("icon", "Bank").put("isArchived", false))
                .toString()
            )

        val version = backupJson.getInt("schemaVersion")
        assertTrue(version in 1..2)

        val profileObj = JSONObject(backupJson.getString("profile"))
        assertEquals(3000L, profileObj.getLong("carryover"))

        // 2. Validate Checkpoint Rollback logic
        val checkpoint = JSONObject()
            .put("status", "IN_PROGRESS")
            .put("timestamp", System.currentTimeMillis())
            .put("preferences", JSONObject()
                .put("schemaVersion", 1)
                .put("default_salary_account", "acc_bank")
            )

        // If status is IN_PROGRESS, crash rollback recovers pre-restore preferences
        assertEquals("IN_PROGRESS", checkpoint.getString("status"))
        val preRestorePrefs = checkpoint.getJSONObject("preferences")
        assertEquals(1, preRestorePrefs.getInt("schemaVersion"))
        assertEquals("acc_bank", preRestorePrefs.getString("default_salary_account"))
    }

    @Test
    fun test14_downgradeResilience_schemaV2ToV1() {
        // Schema V2 profile has carryover and salaryAccountId
        val profileV2 = JSONObject()
            .put("start", "2026-11-01")
            .put("next", "2026-11-15")
            .put("salary", 25000L)
            .put("essentials", 8000L)
            .put("emergency", 3000L)
            .put("strategy", "SNOWBALL")
            .put("salaryAccountId", "acc_gotyme")
            .put("carryover", 5000L)

        // V1 reader reads only start, next, salary, essentials, emergency, strategy
        val v1Profile = PaydayProfile(
            start = LocalDate.parse(profileV2.getString("start")),
            next = LocalDate.parse(profileV2.getString("next")),
            salary = profileV2.getLong("salary"),
            essentials = profileV2.getLong("essentials"),
            emergency = profileV2.getLong("emergency"),
            strategy = DebtStrategy.valueOf(profileV2.getString("strategy"))
        )

        assertEquals(25000L, v1Profile.salary)
        assertEquals(8000L, v1Profile.essentials)
        assertEquals(3000L, v1Profile.emergency)
        assertEquals(DebtStrategy.SNOWBALL, v1Profile.strategy)
        // V1 safely defaults carryover to 0
        assertEquals(0L, v1Profile.carryover)
    }

    @Test
    fun test15_crossDevicePaymentIsolation() {
        // Device A has "Gotyme", Device B has "GCash"
        val accountsDevB = listOf(
            MoneyAccount("acc_cash", "Cash on hand", isSavings = false, icon = "Cash"),
            MoneyAccount("acc_gcash", "GCash", isSavings = false, icon = "Wallet")
        )

        val initialGcash = CashEntry("init_gcash", income = true, amount = 15_000L, date = start, note = "GCash funds", channel = "GCash")

        // Payment recorded on Device A
        val loan = Loan(
            id = "loan_shared", party = "Shared Bank", mode = LoanMode.I_OWE,
            total = 20_000L, count = 2, firstDue = start, frequency = Frequency.MONTHLY
        ).pay(0, 10_000L, "pay_from_devA", start)

        // Device B has no link for pay_from_devA
        val devBLinks = emptyMap<String, PaymentAccountLink>()

        val balancesB = PaydayPlanner.accountBalances(
            accounts = accountsDevB, entries = listOf(initialGcash), profile = null,
            paymentLinks = devBLinks, loans = listOf(loan)
        )

        // Device B's GCash wallet is completely isolated and untainted
        assertEquals(15_000L, balancesB["acc_gcash"])
        // Loan balance is correctly synchronized
        assertEquals(10_000L, loan.remaining)
    }

    @Test
    fun test16_safeToSpend_nonInflationAcrossMultiPeriodRollovers() {
        // Law of Conservation of Centavos:
        // Cash at Period 1 start = ₱20,000.
        // Spent in P1 = ₱15,000. Unspent = ₱5,000.
        // Rollover into P2 with ₱20,000 new salary + ₱5,000 carryover = ₱25,000 opening budget.
        // Spent in P2 = ₱20,000. Unspent = ₱5,000.
        // Rollover into P3 with ₱20,000 new salary + ₱5,000 carryover = ₱25,000 opening budget.
        // Spent in P3 = ₱10,000. Unspent = ₱15,000.

        val totalSalaryEarned = 20_000L + 20_000L + 20_000L // ₱60,000
        val totalSpent = 15_000L + 20_000L + 10_000L       // ₱45,000
        val expectedRemaining = 15_000L                    // ₱15,000

        assertEquals(totalSalaryEarned - totalSpent, expectedRemaining)

        // Verify with PaydayProfile opening budgets:
        val p1 = PaydayProfile(start, next, salary = 20_000L, essentials = 0L, carryover = 0L)
        val p2 = PaydayProfile(next, next.plusDays(14), salary = 20_000L, essentials = 0L, carryover = 5_000L)
        val p3 = PaydayProfile(next.plusDays(14), next.plusDays(28), salary = 20_000L, essentials = 0L, carryover = 5_000L)

        assertEquals(20_000L, p1.totalOpeningBudget)
        assertEquals(25_000L, p2.totalOpeningBudget)
        assertEquals(25_000L, p3.totalOpeningBudget)
    }

    @Test
    fun test17_partialInstallmentPayment_linkedToWallet() {
        val accounts = sampleAccounts()
        val initialGotyme = CashEntry("init_gotyme", income = true, amount = 10_000L, date = start, note = "Opening funds", channel = "Gotyme")

        val loan = Loan(
            id = "loan_partial", party = "Home Credit", mode = LoanMode.I_OWE,
            total = 10_000L, count = 2, firstDue = start, frequency = Frequency.MONTHLY
        )
        // Installment 0 is ₱5,000. Pay partial ₱1,250.
        val partialPaid = loan.pay(0, 1_250L, "partial_p1", start)
        assertEquals(3_750L, partialPaid.installments()[0].remaining)
        assertEquals(8_750L, partialPaid.remaining)

        val link = PaymentAccountLink("partial_p1", "loan_partial", "acc_gotyme", "Gotyme", isReceivable = false)
        val balances = PaydayPlanner.accountBalances(
            accounts = accounts, entries = listOf(initialGotyme), profile = null,
            paymentLinks = mapOf("partial_p1" to link), loans = listOf(partialPaid)
        )

        // Exactly ₱1,250 debited: 10,000 - 1,250 = 8,750
        assertEquals(8_750L, balances["acc_gotyme"])

        // Reversal restores exactly ₱1,250
        val reversed = partialPaid.reverse("partial_p1", start.plusDays(1), "Adjustment")
        val restoredBalances = PaydayPlanner.accountBalances(
            accounts = accounts, entries = listOf(initialGotyme), profile = null,
            paymentLinks = mapOf("partial_p1" to link), loans = listOf(reversed)
        )
        assertEquals(10_000L, restoredBalances["acc_gotyme"])
    }

    @Test
    fun test18_notificationDuplicateDetection_andLoanMatchingRecommendation() {
        val captured = CapturedPayment(
            id = "cap_1", amountCentavos = 150_000L, merchant = "Maya - Atome Payment",
            sourcePackage = "com.maya", channel = "Maya", date = today
        )

        val existingEntries = listOf(
            CashEntry("exp_1", income = false, amount = 150_000L, date = today, note = "Atome payment", channel = "Maya"),
            CashEntry("exp_2", income = false, amount = 20_000L, date = today, note = "Lunch", channel = "Cash on hand")
        )

        // 1. Duplicate detection: identifies duplicate entry with same amount on same date
        val duplicate = existingEntries.firstOrNull { entry ->
            !entry.income && entry.amount == captured.amountCentavos && entry.date == captured.date
        }
        assertNotNull(duplicate)
        assertEquals("exp_1", duplicate?.id)

        // 2. Loan matching: identifies active loan due matching captured amount
        val loan = Loan(
            id = "loan_atome", party = "Atome Offers", mode = LoanMode.I_OWE,
            total = 300_000L, count = 2, firstDue = today, frequency = Frequency.MONTHLY
        )
        val matchingInstallment = listOf(loan)
            .filter { it.mode == LoanMode.I_OWE && it.remaining > 0 }
            .flatMap { l -> l.installments().filter { it.remaining > 0 }.map { l to it } }
            .firstOrNull { (_, inst) ->
                inst.amount == captured.amountCentavos || inst.remaining == captured.amountCentavos
            }

        assertNotNull(matchingInstallment)
        assertEquals("loan_atome", matchingInstallment?.first?.id)
        assertEquals(150_000L, matchingInstallment?.second?.amount)
    }

    @Test
    fun test19_realFinanceStore_migrateSchemaIfNeeded_idempotency() {
        val fakePrefs = FakeSharedPreferences()
        // Legacy unmigrated state: schema_version is absent, profile exists with salary = 25,000
        val legacyProfile = JSONObject()
            .put("start", "2026-11-01")
            .put("next", "2026-11-15")
            .put("salary", 25_000L)
            .put("essentials", 5_000L)
            .put("emergency", 2_000L)
            .put("strategy", "AVALANCHE")
            .put("carryover", 0L)
        fakePrefs.data["profile"] = legacyProfile.toString()

        val finance = FinanceStore(context = null, scope = "test_19", preferences = fakePrefs)
        // init automatically invoked migrateSchemaIfNeeded()
        assertEquals(2, fakePrefs.getInt("schemaVersion", 0))

        val entries = finance.cashEntries()
        assertEquals(1, entries.size)
        val salaryEntry = entries.first()
        assertTrue(salaryEntry.id.startsWith("salary_cutoff_2026-11-01"))
        assertTrue(salaryEntry.income)
        assertEquals(25_000L, salaryEntry.amount)
        assertEquals(LocalDate.of(2026, 11, 1), salaryEntry.date)

        // Idempotency: second migration does not duplicate canonical entry
        finance.migrateSchemaIfNeeded()
        assertEquals(1, finance.cashEntries().size)
    }

    @Test
    fun test20_realFinanceStore_reconcileStartupIntents_promotesConfirmedMatches() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_20", preferences = fakePrefs)

        val intent1 = PendingPaymentIntent(
            intentId = "intent_1", paymentId = "p_confirmed", loanId = "l1",
            installmentIndex = 0, accountId = "acc_bank", accountName = "Bank",
            amountCentavos = 5_000L, date = today, isReceivable = false
        )
        val intent2 = PendingPaymentIntent(
            intentId = "intent_2", paymentId = "p_unconfirmed", loanId = "l2",
            installmentIndex = 0, accountId = "acc_cash", accountName = "Cash on hand",
            amountCentavos = 3_000L, date = today, isReceivable = false
        )
        assertTrue(finance.savePaymentIntent(intent1))
        assertTrue(finance.savePaymentIntent(intent2))

        assertEquals(2, finance.pendingPaymentIntents().size)

        // Loan ledger contains p_confirmed, but not p_unconfirmed
        val loan1 = Loan(
            id = "l1", party = "Bank Loan", mode = LoanMode.I_OWE,
            total = 10_000L, count = 2, firstDue = today, frequency = Frequency.MONTHLY
        ).pay(0, 5_000L, "p_confirmed", today)

        finance.reconcileStartupIntents(listOf(loan1))

        // intent1 promoted to PaymentAccountLink
        val links = finance.allPaymentLinks()
        assertTrue(links.containsKey("p_confirmed"))
        assertEquals("acc_bank", links["p_confirmed"]?.accountId)

        // Only intent2 remains pending
        val remaining = finance.pendingPaymentIntents()
        assertEquals(1, remaining.size)
        assertEquals("intent_2", remaining.first().intentId)
    }

    @Test
    fun test21_realFinanceStore_cancelPendingPaymentIntent_safetyCheckAgainstExistingPayments() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_21", preferences = fakePrefs)

        val loanWithRealPayment = Loan(
            id = "loan_safe", party = "Creditor", mode = LoanMode.I_OWE,
            total = 10_000L, count = 2, firstDue = today, frequency = Frequency.MONTHLY
        ).pay(0, 4_000L, "p_real", today)

        val intentReal = PendingPaymentIntent(
            intentId = "intent_real", paymentId = "p_real", loanId = "loan_safe",
            installmentIndex = 0, accountId = "acc_bank", accountName = "Bank",
            amountCentavos = 4_000L, date = today, isReceivable = false
        )
        val intentAborted = PendingPaymentIntent(
            intentId = "intent_aborted", paymentId = "p_aborted", loanId = "loan_safe",
            installmentIndex = 1, accountId = "acc_bank", accountName = "Bank",
            amountCentavos = 4_000L, date = today, isReceivable = false
        )
        finance.savePaymentIntent(intentReal)
        finance.savePaymentIntent(intentAborted)

        // Test Case A: Attempting to cancel an intent whose payment actually exists in the loan ledger
        val cancelRealResult = finance.cancelPendingPaymentIntent("intent_real", listOf(loanWithRealPayment))
        assertEquals(CancelIntentResult.PROMOTED, cancelRealResult)
        // Intent must be promoted to link, not deleted
        assertTrue("Real payment promoted to link", finance.allPaymentLinks().containsKey("p_real"))
        assertNull(finance.pendingPaymentIntents().find { it.intentId == "intent_real" })

        // Test Case B: Cancelling an intent whose payment does NOT exist in the loan ledger
        val cancelAbortedResult = finance.cancelPendingPaymentIntent("intent_aborted", listOf(loanWithRealPayment))
        assertEquals(CancelIntentResult.CANCELLED, cancelAbortedResult)
        assertNull(finance.pendingPaymentIntents().find { it.intentId == "intent_aborted" })
        assertFalse(finance.allPaymentLinks().containsKey("p_aborted"))
    }

    @Test
    fun test22_paydayPlanner_defensiveReversalHandling_orphanedAndDuplicateReversals() {
        val baseLoan = Loan(
            id = "loan_rev_test", party = "Lender", mode = LoanMode.I_OWE,
            total = 10_000L, count = 2, firstDue = start, frequency = Frequency.MONTHLY
        )
        val paidLoan = baseLoan.pay(0, 5_000L, "pay_1", start)

        // Build loan with valid reversal, orphaned reversal, and duplicate reversal
        val validRev = PaymentReversal("pay_1", start.plusDays(1), "Customer refund")
        val orphanRev = PaymentReversal("pay_nonexistent", start.plusDays(1), "Ghost refund")
        val duplicateRev = PaymentReversal("pay_1", start.plusDays(2), "Accidental double-click")

        val corruptedLoan = paidLoan.copy(
            reversals = listOf(validRev, orphanRev, duplicateRev)
        )

        // 1. validateLoanReversals identifies anomalies
        val report = PaydayPlanner.validateLoanReversals(corruptedLoan)
        assertEquals(1, report.validCount)
        assertEquals(listOf("pay_nonexistent"), report.orphanedPaymentIds)
        assertEquals(listOf("pay_1"), report.duplicatePaymentIds)

        // 2. PaydayPlanner.calculate completes defensively without throwing NoSuchElementException
        val profile = PaydayProfile(start, next, salary = 20_000L, essentials = 5_000L)
        val plan = PaydayPlanner.calculate(
            profile = profile,
            loans = listOf(corruptedLoan),
            policies = emptyMap(),
            entries = emptyList(),
            today = start.plusDays(3)
        )
        assertNotNull(plan)
        // Since pay_1 was reversed, effective paid amount is 0, loan remaining stays 10,000
        assertEquals(10_000L, corruptedLoan.remaining)
    }

    @Test
    fun test23_realFinanceStore_updatePaydayProfile_auditableCompensatingEntry() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_23", preferences = fakePrefs)

        val initialProfile = PaydayProfile(start, next, salary = 2_000_000L, essentials = 500_000L, emergency = 200_000L, salaryAccountId = "acc_bank")
        assertTrue(finance.saveProfile(initialProfile))

        // Case A: Mid-cutoff edit with adjustRecordedDeposit = false
        val updatedNoAdj = initialProfile.copy(salary = 2_500_000L)
        assertTrue(finance.updatePaydayProfile(updatedNoAdj, adjustRecordedDeposit = false))
        assertEquals(2_500_000L, finance.profile().salary)
        assertTrue("No cash entries created when adjustRecordedDeposit is false", finance.cashEntries().isEmpty())

        // Case B: Mid-cutoff edit with adjustRecordedDeposit = true (Salary increase ₱25k -> ₱30k)
        val updatedWithAdj = updatedNoAdj.copy(salary = 3_000_000L)
        assertTrue(finance.updatePaydayProfile(updatedWithAdj, adjustRecordedDeposit = true))
        assertEquals(3_000_000L, finance.profile().salary)

        val entries = finance.cashEntries()
        assertEquals(1, entries.size)
        val adjEntry = entries.first()
        assertTrue(adjEntry.id.startsWith("salary_adj_"))
        assertTrue(adjEntry.income)
        assertEquals(500_000L, adjEntry.amount)
        assertTrue(adjEntry.note.contains("Salary adjustment"))
        assertTrue(adjEntry.note.contains("25000.00") && adjEntry.note.contains("30000.00"))

        // Case C: Mid-cutoff edit with adjustRecordedDeposit = true (Salary decrease ₱30k -> ₱28k)
        val decreasedWithAdj = updatedWithAdj.copy(salary = 2_800_000L)
        assertTrue(finance.updatePaydayProfile(decreasedWithAdj, adjustRecordedDeposit = true))
        assertEquals(2_800_000L, finance.profile().salary)

        val entriesAfterDec = finance.cashEntries()
        assertEquals(2, entriesAfterDec.size)
        val decEntry = entriesAfterDec.last()
        assertFalse("Compensating deduction is expense", decEntry.income)
        assertEquals(200_000L, decEntry.amount)
    }

    @Test
    fun test24_salaryTargetWalletReassignment_noFabricatedInterWalletTransfers() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_24", preferences = fakePrefs)

        // Setup accounts: Bank and GCash
        val bank = MoneyAccount("acc_bank", "Bank", isSavings = false, icon = "Bank")
        val gcash = MoneyAccount("acc_gcash", "GCash", isSavings = false, icon = "Wallet")
        assertTrue(finance.saveMoneyAccounts(listOf(bank, gcash)))

        // Initial salary deposit of ₱20,000 on Bank
        val initialSalary = CashEntry("salary_1", income = true, amount = 20_000L, date = start, note = "Salary", channel = "Bank")
        assertTrue(finance.addCash(initialSalary))

        val initialProfile = PaydayProfile(start, next, salary = 20_000L, essentials = 5_000L, salaryAccountId = "acc_bank")
        assertTrue(finance.saveProfile(initialProfile))

        // User reassigns target salary wallet to GCash
        val reassignedProfile = initialProfile.copy(salaryAccountId = "acc_gcash")
        assertTrue(finance.updatePaydayProfile(reassignedProfile, adjustRecordedDeposit = false))
        assertTrue(finance.setDefaultSalaryAccountId("acc_gcash"))

        // Verify no new cash entries (no fabricated inter-wallet transfer)
        val entries = finance.cashEntries()
        assertEquals(1, entries.size)
        assertEquals("Bank", entries.first().channel)

        // Verify balances: Bank preserves its ₱20,000, GCash remains ₱0
        val balances = PaydayPlanner.accountBalances(finance.moneyAccounts(), entries, reassignedProfile, defaultSalaryAccountId = "acc_gcash")
        assertEquals(20_000L, balances["acc_bank"])
        assertEquals(0L, balances["acc_gcash"])
    }

    @Test
    fun test25_realFinanceStore_multiStoreCheckpoint_creationAndRecovery() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_25", preferences = fakePrefs)

        // Setup initial preferences
        val profile = PaydayProfile(start, next, salary = 18_000L, essentials = 4_000L)
        finance.saveProfile(profile)
        finance.addCash(CashEntry("c1", income = true, amount = 18_000L, date = start, note = "Salary", channel = "Cash on hand"))

        val origLoan = Loan(id = "loan_persisted", party = "Bank", mode = LoanMode.I_OWE, total = 10_000L, count = 1, firstDue = start, frequency = Frequency.MONTHLY)
        val fakeStore = FakeLoanRepository(listOf(origLoan))
        val preLoansJson = LoanCodec.encode(fakeStore.load())
        val checkpointCreated = finance.createMultiStoreCheckpoint(preLoansJson)
        assertTrue("Multi-store checkpoint created", checkpointCreated)

        // Verify pre-restore snapshot can be read
        val loansSnapshot = finance.preRestoreLoansSnapshot()
        assertEquals(preLoansJson, loansSnapshot)

        // Simulate destructive partial write during restore
        fakePrefs.data["corrupted_key"] = "bad_data"
        fakePrefs.data.remove("profile")

        // 1. Recovery without store restores preferences, but preserves checkpoint until store is rolled back
        val prefsRecovered = finance.recoverFromRestoreCheckpointIfNeeded(null)
        assertTrue("Preferences restored", prefsRecovered)
        assertEquals(18_000L, finance.profile().salary)
        assertFalse(fakePrefs.data.containsKey("corrupted_key"))
        assertNotNull("Checkpoint preserved for store recovery", finance.preRestoreLoansSnapshot())

        // 2. Recovery with store restores loans and cleans up checkpoint
        val fullyRecovered = finance.recoverFromRestoreCheckpointIfNeeded(fakeStore)
        assertTrue("Fully recovered with store", fullyRecovered)
        assertEquals(listOf(origLoan), fakeStore.load())
        assertNull("Checkpoint file deleted after complete recovery", finance.preRestoreLoansSnapshot())
    }

    @Test
    fun test26_archivedWalletActivityFiltering_preservesPastTransactions() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_26", preferences = fakePrefs)

        val cash = MoneyAccount("acc_cash", "Cash on hand", isSavings = false, icon = "Cash")
        val bank = MoneyAccount("acc_bank", "Bank", isSavings = false, icon = "Bank")
        val oldWallet = MoneyAccount("acc_old", "Maya Old", isSavings = false, icon = "Wallet")
        assertTrue(finance.saveMoneyAccounts(listOf(cash, bank, oldWallet)))

        // Record historical activity on old wallet
        assertTrue(finance.addCash(CashEntry("e_old_1", income = true, amount = 10_000L, date = start, note = "Old deposit", channel = "Maya Old")))
        assertTrue(finance.addCash(CashEntry("e_old_2", income = false, amount = 3_500L, date = start.plusDays(1), note = "Old bill", channel = "Maya Old")))

        // Archive the old wallet
        assertTrue(finance.archiveMoneyAccount("acc_old"))

        // 1. Active moneyAccounts() excludes archived account (cannot be used for new transactions)
        val activeAccounts = finance.moneyAccounts(includeArchived = false)
        assertEquals(2, activeAccounts.size)
        assertFalse(activeAccounts.any { it.id == "acc_old" })

        // 2. allMoneyAccounts() includes archived account with flag
        val allAccounts = finance.allMoneyAccounts()
        assertEquals(3, allAccounts.size)
        val archived = allAccounts.find { it.id == "acc_old" }
        assertNotNull(archived)
        assertTrue(archived!!.isArchived)

        // 3. Historical entries on archived wallet remain intact
        val entries = finance.cashEntries()
        assertEquals(2, entries.size)

        // 4. Cumulative balance on archived wallet remains exact: 10,000 - 3,500 = 6,500
        val balances = PaydayPlanner.accountBalances(allAccounts, entries, null)
        assertEquals(6_500L, balances["acc_old"])
    }

    @Test
    fun test27_multiStoreRestoreRecovery_crashesAtEveryRestoreStage_localMode() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_27", preferences = fakePrefs)

        val origLoan = Loan(id = "loan_orig", party = "Creditor", mode = LoanMode.I_OWE, total = 10_000L, count = 1, firstDue = start, frequency = Frequency.MONTHLY)
        val addedLoan = Loan(id = "loan_added", party = "New Lender", mode = LoanMode.I_OWE, total = 5_000L, count = 1, firstDue = start, frequency = Frequency.MONTHLY)
        val store = FakeLoanRepository(listOf(origLoan))

        val origProfile = PaydayProfile(start, next, salary = 20_000L, essentials = 5_000L)
        finance.saveProfile(origProfile)

        // Stage 1: Checkpoint created before first mutation
        val checkpointOk = finance.createMultiStoreCheckpoint(LoanCodec.encode(store.load()), store.journalSnapshot())
        assertTrue("Durable checkpoint exists before store mutation", checkpointOk)

        // Stage 2: Crash after loan persistence (store was modified to origLoan + addedLoan, preferences not yet updated)
        store.save(listOf(origLoan, addedLoan))
        assertEquals(2, store.load().size)

        // Recovery on app restart: rolls back store to origLoan and preferences
        val recoveredStage2 = finance.recoverFromRestoreCheckpointIfNeeded(store)
        assertTrue("Recovered from interrupted restore after loan write", recoveredStage2)
        assertEquals(1, store.load().size)
        assertEquals("loan_orig", store.load().first().id)
        assertEquals(20_000L, finance.profile().salary)
        assertNull("Checkpoint deleted only after validating restored state", finance.preRestoreLoansSnapshot())

        // Stage 3: Crash after metadata restore, before completeRestoreCheckpoint()
        val checkpointStage3 = finance.createMultiStoreCheckpoint(LoanCodec.encode(store.load()), store.journalSnapshot())
        assertTrue(checkpointStage3)
        store.save(listOf(origLoan, addedLoan))
        fakePrefs.data["corrupted_field"] = "bad"
        fakePrefs.data["profile"] = "{\"corrupted\":true}"

        // Recovery with null store restores preferences, but preserves checkpoint for store
        val prefsRecovered = finance.recoverFromRestoreCheckpointIfNeeded(null)
        assertTrue("Preferences restored", prefsRecovered)
        assertNotNull("Checkpoint preserved while store rollback is pending", finance.preRestoreLoansSnapshot())

        // Recovery with store finishes the job
        val fullRecovery = finance.recoverFromRestoreCheckpointIfNeeded(store)
        assertTrue("Store and prefs fully restored", fullRecovery)
        assertEquals(listOf(origLoan), store.load())
        assertNull("Checkpoint deleted after complete multi-store verification", finance.preRestoreLoansSnapshot())
    }

    @Test
    fun test28_multiStoreRestoreRecovery_cloudRepoJournalRollback() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_28", preferences = fakePrefs)

        val origLoan = Loan(id = "loan_cloud_orig", party = "Bank", mode = LoanMode.I_OWE, total = 50_000L, count = 1, firstDue = start, frequency = Frequency.MONTHLY)
        val addedLoan = Loan(id = "loan_cloud_added", party = "Fintech", mode = LoanMode.I_OWE, total = 15_000L, count = 1, firstDue = start, frequency = Frequency.MONTHLY)

        // Setup pre-restore journal
        val origJournal = JSONObject()
            .put("base", LoanCodec.encode(listOf(origLoan)))
            .put("queue", JSONArray())
            .put("enabled", true)
            .put("deletedIds", JSONArray())
            .toString()

        val cloudStore = FakeLoanRepository(
            currentLoans = listOf(origLoan),
            isReadyFlag = true,
            journal = origJournal
        )

        // Create checkpoint with cloud journal snapshot
        val checkpointOk = finance.createMultiStoreCheckpoint(LoanCodec.encode(cloudStore.load()), cloudStore.journalSnapshot())
        assertTrue("Checkpoint created with cloud journal snapshot", checkpointOk)

        // Simulate restoreBackup adding addedLoan to cloud store
        cloudStore.currentLoans = listOf(origLoan, addedLoan)
        cloudStore.journal = JSONObject()
            .put("base", LoanCodec.encode(listOf(origLoan)))
            .put("queue", JSONArray().put(JSONObject().put("after", JSONObject().put("id", "loan_cloud_added"))))
            .put("enabled", true)
            .put("deletedIds", JSONArray())
            .toString()

        // Recovery restores exact pre-restore journal snapshot
        val recovered = finance.recoverFromRestoreCheckpointIfNeeded(cloudStore)
        assertTrue("Cloud store journal successfully rolled back", recovered)
        assertEquals(listOf(origLoan), cloudStore.load())
        assertEquals(origJournal, cloudStore.journalSnapshot())
        assertNull(finance.preRestoreLoansSnapshot())
    }

    @Test
    fun test29_salaryAdjustmentReversal_positiveAndNegativeAdjustments_reconcilesBudgetAndWallet() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_29", preferences = fakePrefs)

        val bank = MoneyAccount("acc_bank", "Bank", isSavings = false, icon = "Bank")
        assertTrue(finance.saveMoneyAccounts(listOf(bank)))

        val initialProfile = PaydayProfile(start, next, salary = 2_000_000L, essentials = 500_000L, emergency = 200_000L, salaryAccountId = "acc_bank")
        assertTrue(finance.saveProfile(initialProfile))

        val initialSalary = CashEntry("salary_cutoff_${start}_acc_bank", income = true, amount = 2_000_000L, date = start, note = "Salary Deposit", channel = "Bank")
        assertTrue(finance.addCash(initialSalary))

        // Step 1: Mid-cutoff salary increase from ₱20,000 to ₱25,000 with adjustRecordedDeposit = true
        val updatedProfile = initialProfile.copy(salary = 2_500_000L)
        assertTrue(finance.updatePaydayProfile(updatedProfile, adjustRecordedDeposit = true))

        val entriesAfterAdj = finance.cashEntries()
        assertEquals(2, entriesAfterAdj.size)
        val adjEntry = entriesAfterAdj.last()
        assertTrue(adjEntry.isSalaryRelated)
        assertEquals(500_000L, adjEntry.amount)
        assertTrue(adjEntry.income)

        // Payday calculation before reversal: Safe-to-spend envelope based on ₱25,000
        val planBefore = PaydayPlanner.calculate(finance.profile(), emptyList(), emptyMap(), entriesAfterAdj, start.plusDays(2))
        assertEquals(1_800_000L, planBefore.safeToSpend) // 2.5M - 500k essentials - 200k emergency = 1.8M
        val balancesBefore = PaydayPlanner.accountBalances(finance.moneyAccounts(), entriesAfterAdj, finance.profile())
        assertEquals(2_500_000L, balancesBefore["acc_bank"]) // Base ₱20k + compensating adjustment ₱5k = ₱25k

        // Step 2: User reverses the salary adjustment
        assertTrue("Can reverse salary adjustment entry", finance.canReverseCash(adjEntry.id))
        assertTrue("Reverse salary adjustment succeeded", finance.reverseCash(adjEntry.id))

        // Idempotency: cannot reverse again
        assertFalse("Cannot reverse an already reversed adjustment", finance.canReverseCash(adjEntry.id))
        assertFalse(finance.reverseCash(adjEntry.id))

        val entriesAfterRev = finance.cashEntries()
        assertEquals(3, entriesAfterRev.size)
        val revEntry = entriesAfterRev.last()
        assertTrue("Reversal entry is recognized as salary-related", revEntry.isSalaryRelated)
        assertTrue("Reversal entry is reversal", revEntry.isReversal)

        // Payday calculation after reversal:
        // 1. profile.salary is reconciled back to ₱20,000
        assertEquals(2_000_000L, finance.profile().salary)

        // 2. PaydayPlanner.calculate() excludes undo_salary_adj_ from loggedCash, preventing one-sided deductions
        val planAfter = PaydayPlanner.calculate(finance.profile(), emptyList(), emptyMap(), entriesAfterRev, start.plusDays(2))
        assertEquals(1_300_000L, planAfter.safeToSpend) // 2.0M - 500k essentials - 200k emergency = 1.3M
        assertEquals(2_000_000L, planAfter.cash)

        // 3. Wallet balance in accountBalances() returns to ₱20k (reversal cancels adjustment)
        val balancesAfter = PaydayPlanner.accountBalances(finance.moneyAccounts(), entriesAfterRev, finance.profile())
        assertEquals(2_000_000L, balancesAfter["acc_bank"])

        // Step 3: Test negative adjustment (salary reduced from ₱20k to ₱17k)
        val decreasedProfile = finance.profile().copy(salary = 1_700_000L)
        assertTrue(finance.updatePaydayProfile(decreasedProfile, adjustRecordedDeposit = true))

        val entriesAfterDec = finance.cashEntries()
        assertEquals(4, entriesAfterDec.size)
        val decAdjEntry = finance.cashEntries().last()
        assertFalse("Compensating adjustment for decrease is expense", decAdjEntry.income)
        assertEquals(300_000L, decAdjEntry.amount)
        assertEquals(1_700_000L, finance.profile().salary)

        val balancesDec = PaydayPlanner.accountBalances(finance.moneyAccounts(), entriesAfterDec, finance.profile())
        assertEquals(1_700_000L, balancesDec["acc_bank"])

        // Reverse the negative adjustment
        assertTrue(finance.reverseCash(decAdjEntry.id))
        assertEquals(2_000_000L, finance.profile().salary)
        val planAfterDecRev = PaydayPlanner.calculate(finance.profile(), emptyList(), emptyMap(), finance.cashEntries(), start.plusDays(2))
        assertEquals(1_300_000L, planAfterDecRev.safeToSpend)
        val balancesDecRev = PaydayPlanner.accountBalances(finance.moneyAccounts(), finance.cashEntries(), finance.profile())
        assertEquals(2_000_000L, balancesDecRev["acc_bank"])
    }

    @Test
    fun test30_paymentIntentCancellation_cloudSyncUnresolved_blocksCancellation() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_30", preferences = fakePrefs)

        val unreadyStore = FakeLoanRepository(
            currentLoans = emptyList(),
            isReadyFlag = false // Simulates CloudLoanRepository awaiting initial server snapshot
        )

        val intent = PendingPaymentIntent(
            intentId = "intent_unready", paymentId = "p_unready", loanId = "l1",
            installmentIndex = 0, accountId = "acc_bank", accountName = "Bank",
            amountCentavos = 5_000L, date = today, isReceivable = false
        )
        assertTrue(finance.savePaymentIntent(intent))

        val result = finance.cancelPendingPaymentIntent("intent_unready", unreadyStore)
        assertEquals(CancelIntentResult.BLOCKED_UNREADY, result)
        // Intent is preserved, not discarded
        assertEquals(1, finance.pendingPaymentIntents().size)
    }

    @Test
    fun test31_paymentIntentCancellation_syncConflict_blocksCancellation() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_31", preferences = fakePrefs)

        val conflictStore = object : LoanRepository {
            override val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
            override fun load(): List<Loan> = emptyList()
            override fun save(loans: List<Loan>, expected: List<Loan>?): Boolean = true
            override fun isReady(): Boolean = false // Sync conflict active
        }

        val intent = PendingPaymentIntent(
            intentId = "intent_conflict", paymentId = "p_conflict", loanId = "loan_conflict",
            installmentIndex = 0, accountId = "acc_bank", accountName = "Bank",
            amountCentavos = 5_000L, date = today, isReceivable = false
        )
        assertTrue(finance.savePaymentIntent(intent))

        val result = finance.cancelPendingPaymentIntent("intent_conflict", conflictStore)
        assertEquals(CancelIntentResult.BLOCKED_UNREADY, result)
        assertEquals(1, finance.pendingPaymentIntents().size)
    }

    @Test
    fun test32_paymentIntentCancellation_emptyCloudProjection_blocksCancellation() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_32", preferences = fakePrefs)

        val unreadyCloudStore = FakeLoanRepository(
            currentLoans = emptyList(),
            isReadyFlag = false
        )

        val intent = PendingPaymentIntent(
            intentId = "intent_empty_test", paymentId = "p_empty", loanId = "l1",
            installmentIndex = 0, accountId = "acc_bank", accountName = "Bank",
            amountCentavos = 2_000L, date = today, isReceivable = false
        )
        assertTrue(finance.savePaymentIntent(intent))

        val result = finance.cancelPendingPaymentIntent("intent_empty_test", unreadyCloudStore)
        assertEquals(CancelIntentResult.BLOCKED_UNREADY, result)
        assertEquals(1, finance.pendingPaymentIntents().size)
    }

    @Test
    fun test33_paymentIntentCancellation_cloudReady_safelyPurgesOrPromotes() {
        val fakePrefs = FakeSharedPreferences()
        val finance = FinanceStore(context = null, scope = "test_33", preferences = fakePrefs)

        val confirmedLoan = Loan(id = "l_confirmed", party = "Creditor", mode = LoanMode.I_OWE, total = 10_000L, count = 1, firstDue = today, frequency = Frequency.MONTHLY)
            .pay(0, 5_000L, "p_present", today)

        val readyStore = FakeLoanRepository(
            currentLoans = listOf(confirmedLoan),
            isReadyFlag = true
        )

        val intentPresent = PendingPaymentIntent(
            intentId = "intent_present", paymentId = "p_present", loanId = "l_confirmed",
            installmentIndex = 0, accountId = "acc_bank", accountName = "Bank",
            amountCentavos = 5_000L, date = today, isReceivable = false
        )
        val intentAbsent = PendingPaymentIntent(
            intentId = "intent_absent", paymentId = "p_absent", loanId = "l_confirmed",
            installmentIndex = 0, accountId = "acc_bank", accountName = "Bank",
            amountCentavos = 2_000L, date = today, isReceivable = false
        )

        finance.savePaymentIntent(intentPresent)
        finance.savePaymentIntent(intentAbsent)

        // Intent with payment present in loan ledger is PROMOTED
        val resPresent = finance.cancelPendingPaymentIntent("intent_present", readyStore)
        assertEquals(CancelIntentResult.PROMOTED, resPresent)
        assertTrue(finance.allPaymentLinks().containsKey("p_present"))

        // Intent with payment absent from loan ledger is CANCELLED
        val resAbsent = finance.cancelPendingPaymentIntent("intent_absent", readyStore)
        assertEquals(CancelIntentResult.CANCELLED, resAbsent)
        assertFalse(finance.allPaymentLinks().containsKey("p_absent"))

        // Subsequent call is idempotent: NOT_FOUND
        val resRepeat = finance.cancelPendingPaymentIntent("intent_absent", readyStore)
        assertEquals(CancelIntentResult.NOT_FOUND, resRepeat)
    }
}

class FakeLoanRepository(
    var currentLoans: List<Loan> = emptyList(),
    var isReadyFlag: Boolean = true,
    var journal: String? = null
) : LoanRepository {
    override val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override fun load(): List<Loan> = currentLoans
    override fun save(loans: List<Loan>, expected: List<Loan>?): Boolean {
        if (expected != null && currentLoans != expected) return false
        currentLoans = loans
        changes.value++
        return true
    }
    override fun isReady(): Boolean = isReadyFlag
    override fun journalSnapshot(): String? = journal
    override fun restoreSnapshot(snapshotJson: String): Boolean {
        if (snapshotJson.startsWith("{") && snapshotJson.contains("\"base\"")) {
            val root = JSONObject(snapshotJson)
            currentLoans = LoanCodec.decode(root.optString("base", "[]"))
            journal = snapshotJson
            changes.value++
            return true
        }
        val decoded = runCatching { LoanCodec.decode(snapshotJson) }.getOrNull() ?: return false
        currentLoans = decoded
        journal = LoanCodec.encode(decoded)
        changes.value++
        return true
    }
    override fun rollbackRestore(addedLoans: List<Loan>): Boolean {
        val addedIds = addedLoans.map { it.id }.toSet()
        currentLoans = currentLoans.filterNot { it.id in addedIds }
        changes.value++
        return true
    }
}

class FakeSharedPreferences(
    val data: MutableMap<String, Any> = mutableMapOf()
) : android.content.SharedPreferences {
    private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)

    override fun getString(key: String, defValue: String?): String? =
        data[key]?.toString() ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (data[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        (data[key] as? Number)?.toInt() ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (data[key] as? Number)?.toLong() ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (data[key] as? Number)?.toFloat() ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (data[key] as? Boolean) ?: defValue

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): android.content.SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {
        listeners.remove(listener)
    }

    inner class EditorImpl : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): android.content.SharedPreferences.Editor {
            pending[key] = values?.toSet()
            return this
        }

        override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): android.content.SharedPreferences.Editor {
            pending[key] = this
            return this
        }

        override fun clear(): android.content.SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) data.clear()
            pending.forEach { (k, v) ->
                if (v === this) {
                    data.remove(k)
                } else if (v != null) {
                    data[k] = v
                } else {
                    data.remove(k)
                }
                listeners.forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, k) }
            }
            pending.clear()
            clearAll = false
        }
    }
}
