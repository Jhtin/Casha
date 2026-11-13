package com.example.casha

import com.example.casha.data.AiProposal
import com.example.casha.data.GeminiProposalParser
import com.example.casha.data.GeminiPromptContext
import com.example.casha.data.PaymentSuggestion
import com.example.casha.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GeminiClientTest {

    private fun sampleLoans(): List<Loan> {
        val today = LocalDate.of(2026, 11, 3)
        return listOf(
            Loan(
                id = "loan-1",
                party = "Alice Smith",
                mode = LoanMode.I_OWE,
                total = 100_000L,
                count = 2,
                firstDue = today.plusDays(5),
                frequency = Frequency.MONTHLY
            ),
            Loan(
                id = "loan-2",
                party = "Bob Jones",
                mode = LoanMode.OWED_TO_ME,
                total = 50_000L,
                count = 1,
                firstDue = today.plusDays(10),
                frequency = Frequency.MONTHLY
            )
        )
    }

    @Test
    fun parseAdviceProposal() {
        val json = JSONObject()
            .put("kind", "ADVICE")
            .put("loan", "")
            .put("amountCentavos", 0)
            .put("party", "")
            .put("installments", 0)
            .put("firstDue", "")
            .put("frequency", "MONTHLY")
            .put("mode", "I_OWE")
            .put("explanation", "Here is advice regarding L1.")
            .put("channel", "Cash on hand")
            .toString()

        val proposal = GeminiProposalParser.parse(json, sampleLoans())
        assertEquals("ADVICE", proposal.kind)
        assertTrue(proposal.explanation.contains("Alice Smith"))
        assertEquals("Cash on hand", proposal.channel)
    }

    @Test
    fun parseExpenseProposalWithSpecificChannel() {
        val json = JSONObject()
            .put("kind", "EXPENSE")
            .put("loan", "")
            .put("amountCentavos", 45000)
            .put("party", "")
            .put("installments", 0)
            .put("firstDue", "")
            .put("frequency", "MONTHLY")
            .put("mode", "I_OWE")
            .put("explanation", "Food · Groceries")
            .put("channel", "E-Wallet")
            .toString()

        val proposal = GeminiProposalParser.parse(json, sampleLoans())
        assertEquals("EXPENSE", proposal.kind)
        assertEquals(45000L, proposal.amount)
        assertEquals("E-Wallet", proposal.channel)
        assertEquals("Food · Groceries", proposal.explanation)
    }

    @Test
    fun parseIncomeProposalIntoSavingsChannel() {
        val json = JSONObject()
            .put("kind", "INCOME")
            .put("loan", "")
            .put("amountCentavos", 200_000)
            .put("party", "")
            .put("installments", 0)
            .put("firstDue", "")
            .put("frequency", "MONTHLY")
            .put("mode", "I_OWE")
            .put("explanation", "Extra freelance earnings saved into vault")
            .put("channel", "Savings")
            .toString()

        val proposal = GeminiProposalParser.parse(json, sampleLoans())
        assertEquals("INCOME", proposal.kind)
        assertEquals(200_000L, proposal.amount)
        assertEquals("Savings", proposal.channel)
    }

    @Test
    fun parsePaymentProposal() {
        val loans = sampleLoans()
        val json = JSONObject()
            .put("kind", "PAYMENT")
            .put("loan", "L1")
            .put("amountCentavos", 50000)
            .put("party", "")
            .put("installments", 0)
            .put("firstDue", "")
            .put("frequency", "MONTHLY")
            .put("mode", "I_OWE")
            .put("explanation", "Paying ₱500.00 for L1 installment")
            .put("channel", "Bank")
            .toString()

        val proposal = GeminiProposalParser.parse(json, loans)
        assertEquals("PAYMENT", proposal.kind)
        assertEquals(50000L, proposal.amount)
        assertEquals("Bank", proposal.channel)
        assertEquals("loan-1", proposal.target?.id)
    }

    @Test
    fun promptContextIncludesAccountsSavingsAndCalendar() {
        val today = LocalDate.of(2026, 11, 3)
        val loans = sampleLoans()
        val profile = PaydayProfile(
            start = today.minusDays(5),
            next = today.plusDays(10),
            salary = 3_000_000L,
            essentials = 500_000L
        )
        val entries = listOf(
            CashEntry("e1", true, 500_000L, today.minusDays(2), "Salary", MoneyAccount.DEFAULT_CASH),
            CashEntry("e2", true, 1_000_000L, today.minusDays(1), "Savings deposit", "Savings"),
            CashEntry("e3", false, 150_000L, today, "Food", "E-Wallet")
        )
        val accounts = MoneyAccount.defaultAccounts()
        val savingsNames = setOf("Savings")

        val context = GeminiPromptContext.build(
            command = "How much can I spend and when are my next payments?",
            loans = loans,
            profile = profile,
            entries = entries,
            policies = emptyMap(),
            today = today,
            accounts = accounts,
            savingsAccountNames = savingsNames
        )

        // Check accounts array
        val accountsArray = context.getJSONArray("accounts")
        assertEquals(4, accountsArray.length())
        var savingsFound = false
        for (i in 0 until accountsArray.length()) {
            val acc = accountsArray.getJSONObject(i)
            if (acc.getString("name") == "Savings") {
                savingsFound = true
                assertTrue(acc.getBoolean("isSavings"))
                assertEquals(1_000_000L, acc.getLong("balanceCentavos"))
            }
        }
        assertTrue(savingsFound)

        // Check savings vault
        val savingsVault = context.getJSONObject("savingsVault")
        assertEquals(1_000_000L, savingsVault.getLong("totalCentavos"))

        // Check calendar
        val calendar = context.getJSONArray("calendar")
        assertTrue(calendar.length() >= 2)
        val firstDue = calendar.getJSONObject(0)
        assertEquals("L1", firstDue.getString("loan"))
        assertEquals("DUE_SOON", firstDue.getString("status"))
        assertEquals(50_000L, firstDue.getLong("amountCentavos"))

        // Budget safeToSpend excludes savings
        val budget = context.getJSONObject("budget")
        // Safe to spend should not count the 1,000,000L savings deposit as extra spending income
        assertEquals(500_000L, budget.getLong("cutoffExtraIncomeCentavos"))
    }

    @Test
    fun promptContextIncludesAllAppFeaturesPendingReceiptsAndUpcomingPaydays() {
        val today = LocalDate.of(2026, 11, 3)
        val loans = sampleLoans()
        val profile = PaydayProfile(
            start = today.minusDays(5),
            next = today.plusDays(10),
            salary = 3_000_000L,
            essentials = 500_000L
        )
        val entries = listOf(
            CashEntry("e1", true, 500_000L, today.minusDays(2), "Salary", MoneyAccount.DEFAULT_CASH)
        )
        val suggestions = listOf(
            PaymentSuggestion("sug-1", 45_000L, today, "Receipt123", "camera", null, "Groceries at supermarket")
        )
        val archivedIds = setOf("loan-2")

        val context = GeminiPromptContext.build(
            command = "What features are available and what is my schedule?",
            loans = loans,
            profile = profile,
            entries = entries,
            policies = emptyMap(),
            today = today,
            accounts = MoneyAccount.defaultAccounts(),
            savingsAccountNames = setOf("Savings"),
            suggestions = suggestions,
            archivedLoanIds = archivedIds
        )

        // Check appFeatures
        val appFeatures = context.getJSONObject("appFeatures")
        assertTrue(appFeatures.has("receiptScanner"))
        assertTrue(appFeatures.has("interactiveTutorial"))
        assertTrue(appFeatures.has("accountWalletsCrud"))
        assertTrue(appFeatures.has("protectedSavingsVault"))
        assertTrue(appFeatures.has("paymentCalendar"))
        assertTrue(appFeatures.has("statementAndReminders"))
        assertTrue(appFeatures.has("interestEstimator"))
        assertTrue(appFeatures.has("ledgerReversals"))
        assertTrue(appFeatures.has("payoffStrategies"))
        assertTrue(appFeatures.has("appLock"))
        assertTrue(appFeatures.has("backupRestore"))
        assertTrue(appFeatures.has("cloudSync"))

        // Check pendingReceipts
        val pendingReceipts = context.getJSONArray("pendingReceipts")
        assertEquals(1, pendingReceipts.length())
        val receipt0 = pendingReceipts.getJSONObject(0)
        assertEquals("sug-1", receipt0.getString("id"))
        assertEquals(45_000L, receipt0.getLong("amountCentavos"))
        assertEquals("camera", receipt0.getString("source"))

        // Check upcoming paydays in budget
        val budget = context.getJSONObject("budget")
        val upcomingPaydays = budget.getJSONArray("upcomingPaydays")
        assertTrue(upcomingPaydays.length() >= 3)
        assertEquals("Current cutoff start", upcomingPaydays.getJSONObject(0).getString("label"))
        assertEquals("Next payday", upcomingPaydays.getJSONObject(1).getString("label"))
        assertEquals("Projected next payday", upcomingPaydays.getJSONObject(2).getString("label"))

        // Check loan snapshot flags
        val loanArray = context.getJSONArray("loans")
        assertEquals(2, loanArray.length())
        val l1 = loanArray.getJSONObject(0)
        assertFalse(l1.getBoolean("isArchived"))
        assertFalse(l1.getBoolean("isFullyPaid"))

        val l2 = loanArray.getJSONObject(1)
        assertTrue(l2.getBoolean("isArchived"))
        assertFalse(l2.getBoolean("isFullyPaid"))
    }

    @Test
    fun promptContextIncludesFormattedPesosAndMultiTurnChatHistory() {
        val today = LocalDate.of(2026, 11, 3)
        val loans = sampleLoans()
        val profile = PaydayProfile(
            start = today.minusDays(5),
            next = today.plusDays(4),
            salary = 0L,
            essentials = 0L
        )
        val entries = listOf(
            CashEntry("e1", true, 400_000L, today.minusDays(1), "Initial cash", "Cash on hand"),
            CashEntry("e2", true, 500_000L, today.minusDays(1), "Gotyme fund", "Gotyme")
        )
        val accounts = listOf(
            MoneyAccount("acc-1", "Cash on hand", false, "Cash"),
            MoneyAccount("acc-2", "Gotyme", false, "Bank")
        )
        val history = listOf(
            com.example.casha.data.AssistantChatTurn(
                question = "i will go to baguio this nov 6 to 8, i estimate 5k will be spent. what will be the remaining money",
                answer = "Your current total available cash is ₱9,000.00."
            )
        )

        val context = GeminiPromptContext.build(
            command = "i have 9000, not 900",
            loans = loans,
            profile = profile,
            entries = entries,
            policies = emptyMap(),
            today = today,
            accounts = accounts,
            savingsAccountNames = emptySet(),
            history = history
        )

        // Verify pre-formatted peso values
        val accountsArray = context.getJSONArray("accounts")
        val cashAcc = accountsArray.getJSONObject(0)
        assertEquals("₱4,000.00", cashAcc.getString("balanceFormatted"))
        assertEquals(4000.0, cashAcc.getDouble("balancePesos"), 0.001)

        val gotymeAcc = accountsArray.getJSONObject(1)
        assertEquals("₱5,000.00", gotymeAcc.getString("balanceFormatted"))
        assertEquals(5000.0, gotymeAcc.getDouble("balancePesos"), 0.001)

        val budget = context.getJSONObject("budget")
        assertEquals("₱9,000.00", budget.getString("totalAvailableCashFormatted"))
        assertEquals("₱9,000.00", budget.getString("safeToSpendFormatted"))
        assertEquals(9000.0, budget.getDouble("safeToSpendPesos"), 0.001)

        // Verify recent chat history is present
        val recentChat = context.getJSONArray("recentChat")
        assertEquals(1, recentChat.length())
        assertEquals("i will go to baguio this nov 6 to 8, i estimate 5k will be spent. what will be the remaining money", recentChat.getJSONObject(0).getString("user"))
    }

    @Test fun cashActivityUsesExactPesoStringsAndTransferClassification() {
        val today = LocalDate.of(2026, 11, 4)
        val context = GeminiPromptContext.build("Did I spend today?", emptyList(),
            PaydayProfile(today, today.plusDays(14), 500000, 0),
            listOf(CashEntry("transfer_out_test", false, 480000, today, "ATM", "Gotyme"),
                CashEntry("transfer_in_test", true, 480000, today, "ATM", MoneyAccount.DEFAULT_CASH),
                CashEntry("atm_fee_test", false, 1800, today, "ATM fee", "Gotyme")), emptyMap(), today)
        val activities = context.getJSONObject("activities")
        val recent = activities.getJSONArray("recentCashEntries")
        val transfer = (0 until recent.length()).map { recent.getJSONObject(it) }.first { it.getString("type") == "WALLET_TRANSFER" }
        assertEquals("₱4,800.00", transfer.getString("amountFormatted"))
        assertEquals(4800.0, transfer.getDouble("amountPesos"), 0.0)
        assertEquals(1800L, context.getJSONObject("budget").getLong("cutoffExpensesCentavos"))
        assertEquals(0L, context.getJSONObject("budget").getLong("cutoffExtraIncomeCentavos"))
        assertEquals("₱18.00", context.getJSONObject("todayCashSummary").getString("expenseFormatted"))
    }
}
