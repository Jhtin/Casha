package com.example.casha.domain

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Currency

enum class DebtStrategy { AVALANCHE, SNOWBALL }
data class DebtPolicy(val annualRateBps: Int = 0, val risk: Int = 1, val allowSplit: Boolean = false) {
    init { require(annualRateBps in 0..100_000 && risk in 0..2) }
}
data class MoneyAccount(
    val id: String,
    val name: String,
    val isSavings: Boolean = false,
    val icon: String = if (isSavings) "Savings" else "Wallet",
    val isArchived: Boolean = false
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,50}")))
        require(name.isNotBlank() && name.length <= 50)
    }
    companion object {
        const val DEFAULT_CASH = "Cash on hand"
        const val DEFAULT_EWALLET = "E-Wallet"
        const val DEFAULT_BANK = "Bank"
        const val DEFAULT_SAVINGS = "Savings"

        fun defaultAccounts(): List<MoneyAccount> = listOf(
            MoneyAccount("acc_cash", DEFAULT_CASH, isSavings = false, icon = "Cash"),
            MoneyAccount("acc_ewallet", DEFAULT_EWALLET, isSavings = false, icon = "Wallet"),
            MoneyAccount("acc_bank", DEFAULT_BANK, isSavings = false, icon = "Bank"),
            MoneyAccount("acc_savings", DEFAULT_SAVINGS, isSavings = true, icon = "Savings")
        )
    }
}
data class CashEntry(
    val id: String,
    val income: Boolean,
    val amount: Long,
    val date: LocalDate,
    val note: String,
    val channel: String = MoneyAccount.DEFAULT_CASH
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && amount in 1..Loan.MAX_AMOUNT && note.length <= 200 && date.year in 1900..2200)
        require(channel.isNotBlank() && channel.length <= 50)
    }
    val isSalaryRelated: Boolean get() = id.removePrefix("undo_").startsWith("salary_")
    val isWalletTransfer: Boolean get() = id.removePrefix("undo_").startsWith("transfer_")
    val isReversal: Boolean get() = id.startsWith("undo_")
}

data class PaymentAccountLink(
    val paymentId: String,
    val loanId: String,
    val accountId: String,
    val accountName: String,
    val isReceivable: Boolean = false,
    val amountCentavos: Long = 0L,
    val date: LocalDate? = null
)

data class HistoricalCutoff(
    val id: String,
    val start: LocalDate,
    val next: LocalDate,
    val baseSalary: Long,
    val carryover: Long,
    val essentials: Long,
    val emergency: Long,
    val strategy: DebtStrategy,
    val salaryAccountId: String?,
    val closedAtMs: Long = System.currentTimeMillis(),
    val finalSafeToSpend: Long = 0L,
    val salaryEntryId: String? = null
)

data class PaydayProfile(
    val start: LocalDate,
    val next: LocalDate,
    val salary: Long,
    val essentials: Long,
    val emergency: Long = 0,
    val strategy: DebtStrategy = DebtStrategy.AVALANCHE,
    val salaryAccountId: String? = null,
    val carryover: Long = 0L
) {
    init {
        require(next.isAfter(start) && ChronoUnit.DAYS.between(start,next) <= 62)
        require(start.year in 1900..2200 && next.year in 1900..2200)
        require(listOf(salary,essentials,emergency,carryover).all { it in 0..Loan.MAX_AMOUNT })
        salaryAccountId?.let { require(it.length <= 50) }
    }
    val totalOpeningBudget: Long get() = (salary + carryover).coerceAtMost(Loan.MAX_AMOUNT)
}
data class DebtAllocation(val loanId: String, val installment: Int, val amount: Long, val due: LocalDate, val sinking: Boolean)
data class PaydayPlan(val cash: Long, val essentials: Long, val emergency: Long, val allocations: List<DebtAllocation>,
                      val safeToSpend: Long, val daily: Long, val shortfall: Long, val unreservedDues: Long)

data class PayoffSimulation(
    val totalRemaining: Long,
    val scheduledDebtFreeDate: LocalDate?,
    val simulatedDebtFreeDate: LocalDate?,
    val daysSaved: Long,
    val cutoffsSaved: Int,
    val firstClearedParty: String?
)

data class CategorySpend(val category: String, val amount: Long, val sharePercent: Int)

data class ReversalIntegrityReport(
    val validCount: Int,
    val orphanedPaymentIds: List<String>,
    val duplicatePaymentIds: List<String>
)

object PaydayPlanner {
    fun validateLoanReversals(loan: Loan): ReversalIntegrityReport {
        val paymentIds = loan.payments.map { it.id }.toSet()
        val seen = mutableSetOf<String>()
        val orphaned = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        var valid = 0
        loan.reversals.forEach { r ->
            if (r.paymentId !in paymentIds) {
                orphaned.add(r.paymentId)
            } else if (!seen.add(r.paymentId)) {
                duplicates.add(r.paymentId)
            } else {
                valid++
            }
        }
        return ReversalIntegrityReport(valid, orphaned, duplicates)
    }

    fun calculate(
        profile: PaydayProfile,
        loans: List<Loan>,
        policies: Map<String,DebtPolicy>,
        entries: List<CashEntry>,
        today: LocalDate,
        savingsAccountNames: Set<String> = emptySet()
    ): PaydayPlan {
        fun inside(date:LocalDate)=!date.isBefore(profile.start) && date.isBefore(profile.next)
        // Entries in designated savings accounts are safe and protected; they do not alter regular cutoff safe-to-spend cash.
        // Canonical salary entries and their reversals are the physical wallet counterpart of profile.salary and are excluded to prevent double-counting.
        val spendableEntries = entries.filter { it.channel !in savingsAccountNames && !it.isSalaryRelated }
        val loggedCash = spendableEntries.filter { inside(it.date) && !it.date.isAfter(today) }.sumOf { if(it.income) it.amount else -it.amount }
        val actualPayments=loans.sumOf { loan ->
            val paid=loan.payments.filter { inside(it.date) && !it.date.isAfter(today) }.sumOf { it.amount }
            val paymentMap = loan.payments.associateBy { it.id }
            val reversedIds = mutableSetOf<String>()
            val restored = loan.reversals.filter { inside(it.date) && !it.date.isAfter(today) }.sumOf { r ->
                val p = paymentMap[r.paymentId]
                if (p != null && reversedIds.add(r.paymentId)) {
                    p.amount
                } else 0L
            }
            if(loan.mode==LoanMode.I_OWE) -(paid-restored) else paid-restored
        }
        val cash=profile.totalOpeningBudget + loggedCash + actualPayments
        var available=cash.coerceAtLeast(0)
        val essentials=minOf(available,profile.essentials);available-=essentials
        val emergency=minOf(available,profile.emergency);available-=emergency
        val debts=loans.filter { it.mode==LoanMode.I_OWE }.flatMap { l -> l.installments().filter { it.remaining>0 }.map { l to it } }
        val priority=compareBy<Pair<Loan,Installment>> { if(it.second.due.isBefore(profile.start))0 else 1 }
            .thenByDescending { policies[it.first.id]?.risk ?: 1 }.thenBy {it.second.due}.thenBy { it.first.id }.thenBy { it.second.index }
        val dueNow=debts.filter { it.second.due.isBefore(profile.next) }.sortedWith(priority)
        val allocations=mutableListOf<DebtAllocation>()
        dueNow.forEach { (loan,item) ->
            val amount=minOf(available,item.remaining);available-=amount
            if(amount>0) allocations+=DebtAllocation(loan.id,item.index,amount,item.due,false)
        }
        val period=ChronoUnit.DAYS.between(profile.start,profile.next)
        debts.filter { (_,item) -> !item.due.isBefore(profile.next) && item.due.isBefore(profile.next.plusDays(period)) }
            .filter { policies[it.first.id]?.allowSplit==true }.sortedWith(priority).forEach { (loan,item) ->
                val amount=minOf(available,(item.remaining+1)/2);available-=amount
                if(amount>0) allocations+=DebtAllocation(loan.id,item.index,amount,item.due,true)
            }
        // A strategy recommendation uses surplus without quietly removing the daily allowance.
        val unreserved=dueNow.sumOf { it.second.remaining } - allocations.filterNot { it.sinking }.sumOf { it.amount }
        val deficit=(-cash).coerceAtLeast(0)+(profile.essentials-essentials)+(profile.emergency-emergency)+unreserved
        val days=ChronoUnit.DAYS.between(maxOf(today,profile.start),profile.next).coerceAtLeast(1)
        return PaydayPlan(cash,essentials,emergency,allocations,available,available/days,deficit,unreserved)
    }

    fun surplusTarget(loans:List<Loan>,policies:Map<String,DebtPolicy>,strategy:DebtStrategy):Loan? {
        val active=loans.filter { it.mode==LoanMode.I_OWE && it.remaining>0 }
        return if(strategy==DebtStrategy.AVALANCHE) active.sortedWith(compareByDescending<Loan>{policies[it.id]?.annualRateBps?:0}.thenBy { it.remaining }).firstOrNull()
            else active.minByOrNull { it.remaining }
    }

    fun nextPayday(from:LocalDate,frequency:Frequency):LocalDate=when(frequency) {
        Frequency.WEEKLY -> from.plusWeeks(1)
        Frequency.FORTNIGHTLY -> from.plusWeeks(2)
        Frequency.MONTHLY -> from.plusMonths(1)
        Frequency.SEMIMONTHLY -> if(from.dayOfMonth<15) from.withDayOfMonth(15)
            else if(from.dayOfMonth<from.lengthOfMonth()) from.withDayOfMonth(from.lengthOfMonth()) else from.plusMonths(1).withDayOfMonth(15)
    }

    fun inferFrequency(start: LocalDate, next: LocalDate): Frequency {
        val days = ChronoUnit.DAYS.between(start, next)
        return when {
            days <= 8 -> Frequency.WEEKLY
            days in 13..14 && start.dayOfMonth != 15 && start.dayOfMonth != start.lengthOfMonth() -> Frequency.FORTNIGHTLY
            (start.dayOfMonth == 15 || start.dayOfMonth == start.lengthOfMonth() || start.dayOfMonth == 1) && days in 13..17 -> Frequency.SEMIMONTHLY
            else -> Frequency.MONTHLY
        }
    }

    fun rolloverProfile(
        current: PaydayProfile,
        carryOverSafeToSpend: Long,
        baseSalary: Long = current.salary,
        salaryAccountId: String? = current.salaryAccountId
    ): PaydayProfile {
        val freq = inferFrequency(current.start, current.next)
        val newStart = current.next
        val newNext = nextPayday(newStart, freq)
        val newSalary = (baseSalary.coerceAtLeast(0) + carryOverSafeToSpend.coerceAtLeast(0)).coerceAtMost(Loan.MAX_AMOUNT)
        return PaydayProfile(
            start = newStart,
            next = newNext,
            salary = newSalary,
            essentials = current.essentials,
            emergency = current.emergency,
            strategy = current.strategy,
            salaryAccountId = salaryAccountId ?: current.salaryAccountId
        )
    }

    fun simulatePayoff(
        loans: List<Loan>,
        policies: Map<String, DebtPolicy>,
        profile: PaydayProfile,
        extraPerCutoff: Long,
        strategy: DebtStrategy = profile.strategy
    ): PayoffSimulation {
        val active = loans.filter { it.mode == LoanMode.I_OWE && it.remaining > 0 }
        if (active.isEmpty()) return PayoffSimulation(0L, null, null, 0L, 0, null)
        val totalRemaining = active.sumOf { it.remaining }
        val allInstallments = active.flatMap { loan -> loan.installments().filter { it.remaining > 0 }.map { loan to it } }
        val scheduledDate = allInstallments.maxOf { it.second.due }
        val firstTarget = surplusTarget(active, policies, strategy)?.party
        val extra = extraPerCutoff.coerceAtLeast(0L)
        if (extra == 0L) {
            return PayoffSimulation(totalRemaining, scheduledDate, scheduledDate, 0L, 0, firstTarget)
        }
        data class SimInst(val loanId: String, val party: String, val due: LocalDate, var remaining: Long)
        val simList = allInstallments.map { (loan, inst) -> SimInst(loan.id, loan.party, inst.due, inst.remaining) }.toMutableList()
        val freq = inferFrequency(profile.start, profile.next)
        var windowStart = profile.start
        var windowEnd = profile.next
        var lastClearedDate = profile.start
        var firstCleared: String? = null
        val loanIds = active.map { it.id }.toSet()
        val clearedLoans = mutableSetOf<String>()
        var cutoffsUsed = 0
        while (simList.any { it.remaining > 0 } && cutoffsUsed < 600) {
            cutoffsUsed++
            // 1. Pay scheduled installments due inside or before this cutoff window
            simList.filter { it.remaining > 0 && it.due.isBefore(windowEnd) }.sortedBy { it.due }.forEach { inst ->
                inst.remaining = 0L
                val paidDate = maxOf(windowStart, inst.due)
                if (paidDate.isAfter(lastClearedDate)) lastClearedDate = paidDate
            }
            // 2. Apply extra payment per cutoff according to selected strategy
            var extraAvailable = extra
            while (extraAvailable > 0 && simList.any { it.remaining > 0 }) {
                val remainingByLoan = simList.filter { it.remaining > 0 }.groupBy { it.loanId }.mapValues { e -> e.value.sumOf { it.remaining } }
                val targetLoanId = if (strategy == DebtStrategy.AVALANCHE) {
                    remainingByLoan.keys.sortedWith(
                        compareByDescending<String> { policies[it]?.annualRateBps ?: 0 }
                            .thenBy { remainingByLoan[it] ?: Long.MAX_VALUE }
                    ).firstOrNull()
                } else {
                    remainingByLoan.keys.minByOrNull { remainingByLoan[it] ?: Long.MAX_VALUE }
                } ?: break
                val nextInst = simList.filter { it.loanId == targetLoanId && it.remaining > 0 }.minByOrNull { it.due } ?: break
                val pay = minOf(extraAvailable, nextInst.remaining)
                nextInst.remaining -= pay
                extraAvailable -= pay
                if (windowStart.isAfter(lastClearedDate)) lastClearedDate = windowStart
                if (simList.none { it.loanId == targetLoanId && it.remaining > 0 } && clearedLoans.add(targetLoanId) && firstCleared == null) {
                    firstCleared = nextInst.party
                }
            }
            loanIds.forEach { id ->
                if (simList.none { it.loanId == id && it.remaining > 0 } && clearedLoans.add(id) && firstCleared == null) {
                    firstCleared = active.first { it.id == id }.party
                }
            }
            if (simList.none { it.remaining > 0 }) break
            windowStart = windowEnd
            windowEnd = nextPayday(windowStart, freq)
        }
        val simulatedDate = if (lastClearedDate.isBefore(scheduledDate)) lastClearedDate else scheduledDate
        val daysSaved = ChronoUnit.DAYS.between(simulatedDate, scheduledDate).coerceAtLeast(0L)
        val avgCutoffDays = ChronoUnit.DAYS.between(profile.start, profile.next).coerceAtLeast(1L)
        val cutoffsSaved = (daysSaved / avgCutoffDays).toInt()
        return PayoffSimulation(totalRemaining, scheduledDate, simulatedDate, daysSaved, cutoffsSaved, firstCleared ?: firstTarget)
    }

    fun categoryBreakdown(profile: PaydayProfile, entries: List<CashEntry>, today: LocalDate): List<CategorySpend> {
        fun inside(date: LocalDate) = !date.isBefore(profile.start) && date.isBefore(profile.next) && !date.isAfter(today)
        val reversedIds = entries.filter { it.id.startsWith("undo_") }.map { it.id.removePrefix("undo_") }.toSet()
        val expenses = entries.filter { !it.income && !it.isWalletTransfer && !it.id.startsWith("undo_") && it.id !in reversedIds && inside(it.date) }
        if (expenses.isEmpty()) return emptyList()
        val total = expenses.sumOf { it.amount }.coerceAtLeast(1L)
        fun classify(note: String): String {
            val trimmed = note.trim()
            val prefix = trimmed.substringBefore(" · ").trim()
            if (prefix in listOf("Food", "Transport", "Bills", "Shopping")) return prefix
            val lower = trimmed.lowercase()
            return when {
                listOf("food", "coffee", "grocer", "lunch", "dinner", "breakfast", "snack", "kain", "bigas", "ulam").any { lower.contains(it) } -> "Food"
                listOf("transport", "grab", "jeep", "bus", "trike", "gas", "fuel", "toll", "pamasahe", "angkas").any { lower.contains(it) } -> "Transport"
                listOf("bill", "meralco", "water", "maynilad", "internet", "pldt", "globe", "rent", "load", "kuryente", "tubig").any { lower.contains(it) } -> "Bills"
                listOf("shop", "lazada", "shopee", "clothes", "shoes", "mall", "bili").any { lower.contains(it) } -> "Shopping"
                else -> "Other"
            }
        }
        return expenses.groupBy { classify(it.note) }
            .map { (cat, list) ->
                val sum = list.sumOf { it.amount }
                CategorySpend(cat, sum, ((sum * 100L) / total).toInt().coerceIn(1, 100))
            }
            .sortedByDescending { it.amount }
    }

    fun formatStatement(loan: Loan, today: LocalDate = LocalDate.now()): String {
        val fmt = NumberFormat.getCurrencyInstance().apply { currency = Currency.getInstance("PHP") }
        fun php(centavos: Long) = fmt.format(BigDecimal.valueOf(centavos, 2))
        val installments = loan.installments()
        val paidCount = installments.count { it.remaining == 0L }
        val totalPaid = loan.total - loan.remaining
        val next = installments.firstOrNull { it.remaining > 0 }
        val activePayments = loan.payments.filter { p -> loan.reversals.none { it.paymentId == p.id } }
        return buildString {
            appendLine("CASHA STATEMENT OF ACCOUNT")
            appendLine("Date: $today")
            appendLine("Party: ${loan.party} (${if (loan.mode == LoanMode.I_OWE) "Payable / I Owe" else "Receivable / Owed to Me"})")
            appendLine("--------------------------------")
            appendLine("Agreed Total: ${php(loan.total)}")
            appendLine("Total Paid: ${php(totalPaid)}")
            appendLine("Remaining Balance: ${php(loan.remaining)}")
            appendLine("Installments: $paidCount of ${loan.count} paid (${loan.frequency.name.lowercase()})")
            if (next != null) {
                appendLine("Next Due: ${next.due} · ${php(next.remaining)}")
            } else {
                appendLine("Status: Paid in full")
            }
            if (activePayments.isNotEmpty()) {
                appendLine("--------------------------------")
                appendLine("Recorded Payments:")
                activePayments.takeLast(10).forEach { p ->
                    appendLine("• ${p.date}: ${php(p.amount)} (Installment ${p.installment + 1})")
                }
            }
        }.trimEnd()
    }

    /** Total net amount stored in designated savings accounts, net of reversals, including salary if stored in savings. */
    fun savingsBalance(
        entries: List<CashEntry>,
        savingsAccountNames: Set<String>,
        profile: PaydayProfile? = null,
        accounts: List<MoneyAccount> = emptyList()
    ): Long {
        if (savingsAccountNames.isEmpty()) return 0L
        val reversedIds = entries.filter { it.id.startsWith("undo_") }.map { it.id.removePrefix("undo_") }.toSet()
        val valid = entries.filter { it.id !in reversedIds && !it.id.startsWith("undo_") && it.channel in savingsAccountNames }
        val netEntries = valid.sumOf { if (it.income) it.amount else -it.amount }
        val salaryCredit = if (profile != null && profile.salary > 0L && accounts.isNotEmpty()) {
            val target = accounts.firstOrNull { it.id == profile.salaryAccountId || it.name.equals(profile.salaryAccountId, ignoreCase = true) }
            if (target != null && target.name in savingsAccountNames) profile.salary else 0L
        } else 0L
        return (netEntries + salaryCredit).coerceAtLeast(0L)
    }

    /** Net balance per account, net of reversals, loan payments, and receivable collections. */
    fun accountBalances(
        accounts: List<MoneyAccount>,
        entries: List<CashEntry>,
        profile: PaydayProfile? = null,
        defaultSalaryAccountId: String? = null,
        paymentLinks: Map<String, PaymentAccountLink> = emptyMap(),
        loans: List<Loan> = emptyList()
    ): Map<String, Long> {
        val reversedIds = entries.filter { it.id.startsWith("undo_") }.map { it.id.removePrefix("undo_") }.toSet()
        val active = entries.filter { it.id !in reversedIds && !it.id.startsWith("undo_") }
        val targetAccountId = if (profile != null && profile.salary > 0L) {
            accounts.firstOrNull { it.id == profile.salaryAccountId || it.name.equals(profile.salaryAccountId, ignoreCase = true) }?.id
                ?: defaultSalaryAccountId?.let { def -> accounts.firstOrNull { it.id == def }?.id }
                ?: accounts.firstOrNull { it.id == "acc_bank" || it.name.equals(MoneyAccount.DEFAULT_BANK, ignoreCase = true) }?.id
                ?: accounts.firstOrNull { !it.isSavings }?.id
                ?: accounts.firstOrNull()?.id
        } else null

        // Check if entries already contain a canonical salary deposit entry
        val hasCanonicalSalary = active.any { it.isSalaryRelated }
        val authoritativePaymentIds = loans.flatMap { it.payments }.map { it.id }.toSet()

        return accounts.associate { acc ->
            val net = active.filter { it.channel.equals(acc.name, ignoreCase = true) }
                .sumOf { if (it.income) it.amount else -it.amount }

            // Fallback for legacy unmigrated stores/tests: credit profile.salary only if no canonical salary entry exists
            val salaryCredit = if (!hasCanonicalSalary && acc.id == targetAccountId) profile?.salary ?: 0L else 0L

            // Loan payments and receivable collections linked to this wallet
            val seenDebitPayments = mutableSetOf<String>()
            val accountLoanDebits = loans.filter { it.mode == LoanMode.I_OWE }.flatMap { l ->
                val reversedPaymentIds = l.reversals.map { it.paymentId }.toSet()
                l.payments.filter { p ->
                    p.id !in reversedPaymentIds &&
                    seenDebitPayments.add(p.id) &&
                    paymentLinks[p.id]?.let { link ->
                        link.accountId == acc.id || link.accountName.equals(acc.name, ignoreCase = true)
                    } == true
                }
            }.sumOf { it.amount }

            val seenCreditPayments = mutableSetOf<String>()
            val accountReceivableCredits = loans.filter { it.mode == LoanMode.OWED_TO_ME }.flatMap { l ->
                val reversedPaymentIds = l.reversals.map { it.paymentId }.toSet()
                l.payments.filter { p ->
                    p.id !in reversedPaymentIds &&
                    seenCreditPayments.add(p.id) &&
                    paymentLinks[p.id]?.let { link ->
                        link.accountId == acc.id || link.accountName.equals(acc.name, ignoreCase = true)
                    } == true
                }
            }.sumOf { it.amount }

            // Provisional local payment effects when the authoritative loan record is unavailable in current scope
            // (e.g. guest loan payment links migrated to an account before loan migration/sync, or offline local payment).
            // Payments already processed authoritatively in loans are strictly excluded to avoid double-counting.
            val provisionalLinks = paymentLinks.values.filter { link ->
                (link.accountId == acc.id || link.accountName.equals(acc.name, ignoreCase = true)) &&
                link.paymentId !in authoritativePaymentIds &&
                link.amountCentavos > 0L
            }
            val provisionalDebits = provisionalLinks.filter { !it.isReceivable }.sumOf { it.amountCentavos }
            val provisionalCredits = provisionalLinks.filter { it.isReceivable }.sumOf { it.amountCentavos }

            acc.id to (net + salaryCredit - accountLoanDebits - provisionalDebits + accountReceivableCredits + provisionalCredits)
        }
    }
}
