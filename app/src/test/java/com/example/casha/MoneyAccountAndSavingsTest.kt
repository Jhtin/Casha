package com.example.casha

import com.example.casha.domain.*
import com.example.casha.ui.cutoffFunding
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MoneyAccountAndSavingsTest {
    private val start = LocalDate.of(2026, 11, 1)
    private val next = LocalDate.of(2026, 11, 15)
    private val today = LocalDate.of(2026, 11, 3)
    private val profile = PaydayProfile(start, next, salary = 20_000, essentials = 5_000, emergency = 2_000)

    @Test
    fun defaultAccountsContainCashEwalletBankAndSavings() {
        val defaults = MoneyAccount.defaultAccounts()
        assertEquals(4, defaults.size)

        val cash = defaults.first { it.id == "acc_cash" }
        assertEquals(MoneyAccount.DEFAULT_CASH, cash.name)
        assertFalse(cash.isSavings)

        val ewallet = defaults.first { it.id == "acc_ewallet" }
        assertEquals(MoneyAccount.DEFAULT_EWALLET, ewallet.name)
        assertFalse(ewallet.isSavings)

        val bank = defaults.first { it.id == "acc_bank" }
        assertEquals(MoneyAccount.DEFAULT_BANK, bank.name)
        assertFalse(bank.isSavings)

        val savings = defaults.first { it.id == "acc_savings" }
        assertEquals(MoneyAccount.DEFAULT_SAVINGS, savings.name)
        assertTrue(savings.isSavings)
    }

    @Test
    fun savingsIncomeDoesNotInflateSafeToSpend() {
        val savingsNames = setOf(MoneyAccount.DEFAULT_SAVINGS)

        // Base plan with no extra cash entries
        val basePlan = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), emptyList(), today, savingsNames)
        // 20000 - 5000 (essentials) - 2000 (emergency) = 13000 safe to spend
        assertEquals(13_000L, basePlan.safeToSpend)

        // User receives 5,000 PHP directly into Savings account
        val savingsIncome = CashEntry("inc_sav", true, 5_000, today, "Deposited into savings", channel = MoneyAccount.DEFAULT_SAVINGS)
        val planWithSavings = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), listOf(savingsIncome), today, savingsNames)

        // Safe to spend remains 13,000! Money is safe in the savings vault!
        assertEquals(13_000L, planWithSavings.safeToSpend)

        // In contrast, income into Cash on hand DOES increase safe to spend
        val regularIncome = CashEntry("inc_cash", true, 5_000, today, "Side gig cash", channel = MoneyAccount.DEFAULT_CASH)
        val planWithRegularCash = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), listOf(regularIncome), today, savingsNames)
        assertEquals(18_000L, planWithRegularCash.safeToSpend)
    }

    @Test
    fun savingsExpenseDoesNotReduceSafeToSpendAllowance() {
        val savingsNames = setOf(MoneyAccount.DEFAULT_SAVINGS)
        val basePlan = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), emptyList(), today, savingsNames)

        // An emergency expense paid from the savings account
        val savingsExpense = CashEntry("exp_sav", false, 3_000, today, "Emergency repair from savings", channel = MoneyAccount.DEFAULT_SAVINGS)
        val plan = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), listOf(savingsExpense), today, savingsNames)

        // Safe to spend remains unaffected because money came from the protected savings vault
        assertEquals(basePlan.safeToSpend, plan.safeToSpend)

        // Meanwhile, an expense from E-Wallet directly reduces safe to spend
        val walletExpense = CashEntry("exp_wal", false, 3_000, today, "Grocery via GCash", channel = MoneyAccount.DEFAULT_EWALLET)
        val plan2 = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), listOf(walletExpense), today, savingsNames)
        assertEquals(basePlan.safeToSpend - 3_000L, plan2.safeToSpend)
    }

    @Test
    fun transferFromCashToSavingsProtectsMoney() {
        val savingsNames = setOf(MoneyAccount.DEFAULT_SAVINGS)

        // User moves 2,000 from Cash on hand into Savings:
        // 1. Expense from Cash on hand: money leaves regular spending pool
        val outOfCash = CashEntry("t_out", false, 2_000, today, "Transfer to savings", channel = MoneyAccount.DEFAULT_CASH)
        // 2. Income into Savings: money enters savings vault
        val intoSavings = CashEntry("t_in", true, 2_000, today, "Transfer from cash", channel = MoneyAccount.DEFAULT_SAVINGS)

        val entries = listOf(outOfCash, intoSavings)
        val plan = PaydayPlanner.calculate(profile, emptyList(), emptyMap(), entries, today, savingsNames)

        // Safe to spend decreased by 2,000 because it was safely put away in savings!
        assertEquals(11_000L, plan.safeToSpend)

        // Total savings correctly reflects the 2,000
        val savingsBal = PaydayPlanner.savingsBalance(entries, savingsNames)
        assertEquals(2_000L, savingsBal)
    }

    @Test
    fun accountBalancesAreAccuratelyTracked() {
        val accounts = MoneyAccount.defaultAccounts()
        val entries = listOf(
            CashEntry("1", true, 10_000, today, "ATM withdrawal", channel = MoneyAccount.DEFAULT_CASH),
            CashEntry("2", false, 1_500, today, "Lunch", channel = MoneyAccount.DEFAULT_CASH),
            CashEntry("3", true, 5_000, today, "GCash in", channel = MoneyAccount.DEFAULT_EWALLET),
            CashEntry("4", false, 500, today, "Shopee", channel = MoneyAccount.DEFAULT_EWALLET),
            CashEntry("5", true, 25_000, today, "Bank savings deposit", channel = MoneyAccount.DEFAULT_SAVINGS)
        )

        val balances = PaydayPlanner.accountBalances(accounts, entries)
        assertEquals(8_500L, balances["acc_cash"])
        assertEquals(4_500L, balances["acc_ewallet"])
        assertEquals(0L, balances["acc_bank"])
        assertEquals(25_000L, balances["acc_savings"])
    }

    @Test
    fun cutoffFundingExcludesSavingsIncome() {
        val savingsNames = setOf(MoneyAccount.DEFAULT_SAVINGS)
        val entries = listOf(
            CashEntry("c1", true, 3_000, today, "Cash bonus", channel = MoneyAccount.DEFAULT_CASH),
            CashEntry("s1", true, 10_000, today, "Savings interest/deposit", channel = MoneyAccount.DEFAULT_SAVINGS)
        )

        val funding = cutoffFunding(profile, emptyList(), entries, today, savingsNames)
        // 20000 salary + 3000 cash bonus = 23000 (Savings 10,000 is excluded)
        assertEquals(23_000L, funding)
    }

    @Test
    fun salaryGoesToDefaultSalaryWalletWhenUnspecified() {
        val accounts = MoneyAccount.defaultAccounts()
        val profileWithoutExplicitWallet = PaydayProfile(start, next, salary = 25_000L, essentials = 5_000L)
        // With default salary wallet = acc_bank
        val balances = PaydayPlanner.accountBalances(accounts, emptyList(), profileWithoutExplicitWallet, defaultSalaryAccountId = "acc_bank")

        assertEquals(0L, balances["acc_cash"])
        assertEquals(0L, balances["acc_ewallet"])
        assertEquals(25_000L, balances["acc_bank"])
        assertEquals(0L, balances["acc_savings"])
    }

    @Test
    fun salaryGoesToUserPickedWallet() {
        val accounts = MoneyAccount.defaultAccounts()
        // User explicitly picked E-Wallet for salary storage
        val profileWithEwallet = PaydayProfile(start, next, salary = 30_000L, essentials = 5_000L, salaryAccountId = "acc_ewallet")
        val balances = PaydayPlanner.accountBalances(accounts, emptyList(), profileWithEwallet, defaultSalaryAccountId = "acc_bank")

        assertEquals(0L, balances["acc_cash"])
        assertEquals(30_000L, balances["acc_ewallet"])
        assertEquals(0L, balances["acc_bank"])
        assertEquals(0L, balances["acc_savings"])

        // User explicitly picked Cash on hand
        val profileWithCash = PaydayProfile(start, next, salary = 15_000L, essentials = 5_000L, salaryAccountId = "acc_cash")
        val cashBalances = PaydayPlanner.accountBalances(accounts, emptyList(), profileWithCash, defaultSalaryAccountId = "acc_bank")

        assertEquals(15_000L, cashBalances["acc_cash"])
        assertEquals(0L, cashBalances["acc_ewallet"])
        assertEquals(0L, cashBalances["acc_bank"])
    }

    @Test
    fun spendingFromSalaryWalletDecrementsBalanceFromSalary() {
        val accounts = MoneyAccount.defaultAccounts()
        val profile = PaydayProfile(start, next, salary = 20_000L, essentials = 5_000L, salaryAccountId = "acc_bank")
        val entries = listOf(
            CashEntry("e1", false, 3_000L, today, "Electric bill", channel = MoneyAccount.DEFAULT_BANK),
            CashEntry("e2", false, 1_500L, today, "Water bill", channel = MoneyAccount.DEFAULT_BANK)
        )

        val balances = PaydayPlanner.accountBalances(accounts, entries, profile, defaultSalaryAccountId = "acc_bank")
        // 20,000 salary - 4,500 expenses = 15,500 balance remaining in Bank
        assertEquals(15_500L, balances["acc_bank"])
    }

    @Test
    fun rolloverPreservesPickedSalaryWallet() {
        val profile = PaydayProfile(start, next, salary = 20_000L, essentials = 5_000L, salaryAccountId = "acc_ewallet")
        val rolledOver = PaydayPlanner.rolloverProfile(profile, carryOverSafeToSpend = 2_000L)
        assertEquals("acc_ewallet", rolledOver.salaryAccountId)

        // Or rollover can change the destination wallet if specified
        val rolledWithNewWallet = PaydayPlanner.rolloverProfile(profile, carryOverSafeToSpend = 2_000L, salaryAccountId = "acc_bank")
        assertEquals("acc_bank", rolledWithNewWallet.salaryAccountId)
    }
}
