package com.example.casha.data

import com.example.casha.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object GeminiPromptContext {
    fun formatPesos(centavos: Long): String {
        val bd = java.math.BigDecimal.valueOf(centavos, 2)
        val formatted = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(bd)
        return "₱$formatted"
    }

    fun pesos(centavos: Long): Double = java.math.BigDecimal.valueOf(centavos, 2).toDouble()

    fun deanonymize(text:String,loans:List<Loan>):String {
        var result=text
        loans.withIndex().sortedByDescending {it.index}.forEach {(i,loan)->
            val num=i+1
            result=result
                .replace(Regex("(?i)\\bloan\\s+#?$num\\b"),loan.party)
                .replace(Regex("(?i)(?<![\\p{L}\\p{N}])L[-_\\s]?$num(?![\\p{L}\\p{N}])"),loan.party)
        }
        return result
    }

    private fun sanitizeText(input:String,loans:List<Loan>):String {
        var sanitized=input
        loans.withIndex().sortedByDescending {it.value.party.length}.forEach {(i,loan)->
            val alias="L${i+1}"
            val full=loan.party.trim()
            sanitized=sanitized.replace(Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(full)}(?![\\p{L}\\p{N}])"),alias)
            full.split(Regex("\\s+")).filter {it.length>=3}.sortedByDescending {it.length}.forEach {token->
                sanitized=sanitized.replace(Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(token)}(?![\\p{L}\\p{N}])"),alias)
            }
        }
        return sanitized
    }

    private fun redactSensitiveInNote(input:String,loans:List<Loan>):String {
        return sanitizeText(input,loans)
            .replace(Regex("\\b[0-9]{11,19}\\b"),"[REDACTED]")
            .replace(Regex("AIza[A-Za-z0-9_-]{25,}"),"[REDACTED]")
            .replace(Regex("[^\\s]+@[^\\s]+\\.[^\\s]+"),"[REDACTED]")
            .take(120)
    }

    fun build(
        command: String,
        loans: List<Loan>,
        profile: PaydayProfile,
        entries: List<CashEntry>,
        policies: Map<String, DebtPolicy>,
        today: LocalDate,
        accounts: List<MoneyAccount> = emptyList(),
        savingsAccountNames: Set<String> = emptySet(),
        suggestions: List<PaymentSuggestion> = emptyList(),
        archivedLoanIds: Set<String> = emptySet(),
        history: List<AssistantChatTurn> = emptyList()
    ): JSONObject {
        val sanitized=sanitizeText(command,loans)
        require(!Regex("\\b[0-9]{11,19}\\b|AIza[A-Za-z0-9_-]{25,}|[^\\s]+@[^\\s]+\\.[^\\s]+").containsMatchIn(sanitized)) {
            "Remove account numbers, long references, email addresses and API keys from the command. Use loan labels and amounts only."
        }
        val yesterday=today.minusDays(1)
        val snapshot=JSONArray()
        val loanActivities=mutableListOf<Pair<LocalDate,JSONObject>>()
        loans.forEachIndexed {i,loan->
            val alias="L${i+1}"
            val installments=loan.installments()
            val next=installments.firstOrNull {it.remaining>0}
            val policy=policies[loan.id]?:DebtPolicy()
            val paidCentavos=loan.total-loan.remaining
            val paidInstallments=installments.count {it.remaining==0L}
            val upcomingArray=JSONArray()
            installments.filter {it.remaining>0}.take(12).forEach {inst->
                val daysUntil=ChronoUnit.DAYS.between(today,inst.due)
                val status=when {
                    daysUntil<0 -> "OVERDUE"
                    daysUntil==0L -> "DUE_TODAY"
                    daysUntil<=3L -> "DUE_WITHIN_3_DAYS"
                    daysUntil<=7L -> "DUE_SOON"
                    else -> "UPCOMING"
                }
                upcomingArray.put(JSONObject()
                    .put("installmentNumber",inst.index+1)
                    .put("dueDate",inst.due.toString())
                    .put("scheduledFormatted",formatPesos(inst.amount))
                    .put("scheduledPesos",pesos(inst.amount))
                    .put("scheduledCentavos",inst.amount)
                    .put("paidFormatted",formatPesos(inst.paid))
                    .put("paidPesos",pesos(inst.paid))
                    .put("paidCentavos",inst.paid)
                    .put("remainingFormatted",formatPesos(inst.remaining))
                    .put("remainingPesos",pesos(inst.remaining))
                    .put("remainingCentavos",inst.remaining)
                    .put("status",status))
            }
            val paymentsArray=JSONArray()
            loan.payments.takeLast(25).forEach {p->
                val reversal=loan.reversals.find {it.paymentId==p.id}
                val item=JSONObject()
                    .put("installmentNumber",p.installment+1)
                    .put("amountFormatted",formatPesos(p.amount))
                    .put("amountPesos",pesos(p.amount))
                    .put("amountCentavos",p.amount)
                    .put("date",p.date.toString())
                    .put("reversed",reversal!=null)
                if(reversal!=null) {
                    item.put("reversedOn",reversal.date.toString())
                    item.put("reversalReason",redactSensitiveInNote(reversal.reason,loans))
                }
                paymentsArray.put(item)
                loanActivities+=p.date to JSONObject()
                    .put("date",p.date.toString())
                    .put("kind",if(loan.mode==LoanMode.I_OWE)"LOAN_PAYMENT_SENT" else "LOAN_COLLECTION_RECEIVED")
                    .put("loan",alias)
                    .put("installmentNumber",p.installment+1)
                    .put("amountFormatted",formatPesos(p.amount))
                    .put("amountPesos",pesos(p.amount))
                    .put("amountCentavos",p.amount)
                    .put("reversed",reversal!=null)
                if(reversal!=null) {
                    loanActivities+=reversal.date to JSONObject()
                        .put("date",reversal.date.toString())
                        .put("kind","LOAN_PAYMENT_REVERSAL")
                        .put("loan",alias)
                        .put("installmentNumber",p.installment+1)
                        .put("amountFormatted",formatPesos(p.amount))
                        .put("amountPesos",pesos(p.amount))
                        .put("amountCentavos",p.amount)
                        .put("reason",redactSensitiveInNote(reversal.reason,loans))
                }
            }
            snapshot.put(JSONObject()
                .put("loan",alias)
                .put("mode",loan.mode.name)
                .put("totalFormatted",formatPesos(loan.total))
                .put("totalPesos",pesos(loan.total))
                .put("totalCentavos",loan.total)
                .put("paidFormatted",formatPesos(paidCentavos))
                .put("paidPesos",pesos(paidCentavos))
                .put("paidCentavos",paidCentavos)
                .put("remainingFormatted",formatPesos(loan.remaining))
                .put("remainingPesos",pesos(loan.remaining))
                .put("remainingCentavos",loan.remaining)
                .put("installmentCount",loan.count)
                .put("paidInstallments",paidInstallments)
                .put("frequency",loan.frequency.name)
                .put("firstDue",loan.firstDue.toString())
                .put("nextDue",next?.due?.toString()?:"PAID")
                .put("nextAmountFormatted",formatPesos(next?.remaining?:0))
                .put("nextAmountPesos",pesos(next?.remaining?:0))
                .put("nextAmountCentavos",next?.remaining?:0)
                .put("risk",policy.risk)
                .put("annualRateBps",policy.annualRateBps)
                .put("allowSplitReserve",policy.allowSplit)
                .put("isArchived",loan.id in archivedLoanIds)
                .put("isFullyPaid",loan.remaining==0L)
                .put("upcomingInstallments",upcomingArray)
                .put("recordedPayments",paymentsArray))
        }

        val reversedCashIds=entries.filter {it.id.startsWith("undo_")}.map {it.id.removePrefix("undo_")}.toSet()
        val cashArray=JSONArray()
        val allActivities=mutableListOf<Pair<LocalDate,JSONObject>>()
        allActivities.addAll(loanActivities)
        entries.withIndex().sortedWith(compareByDescending<IndexedValue<CashEntry>> {it.value.date}.thenByDescending {it.index}).take(60).forEach {(_,e)->
            val isReversal=e.id.startsWith("undo_")
            val isReversed=e.id in reversedCashIds
            val status=when {
                isReversal -> "REVERSAL_ENTRY"
                isReversed -> "REVERSED"
                else -> "ACTIVE"
            }
            val cleanNote=redactSensitiveInNote(e.note,loans)
            val cashObj=JSONObject()
                .put("date",e.date.toString())
                .put("type",if(isReversal)"REVERSAL" else if(e.isWalletTransfer)"WALLET_TRANSFER" else if(e.income)"INCOME" else "EXPENSE")
                .put("amountFormatted",formatPesos(e.amount))
                .put("amountPesos",pesos(e.amount))
                .put("amountCentavos",e.amount)
                .put("channel",e.channel)
                .put("note",cleanNote)
                .put("status",status)
                .put("inCurrentCutoff",!e.date.isBefore(profile.start)&&e.date.isBefore(profile.next))
            cashArray.put(cashObj)
            allActivities+=e.date to JSONObject()
                .put("date",e.date.toString())
                .put("kind",if(isReversal)"CASH_REVERSAL" else if(e.isWalletTransfer)"WALLET_TRANSFER" else if(e.income)"CASH_INCOME" else "CASH_EXPENSE")
                .put("amountFormatted",formatPesos(e.amount))
                .put("amountPesos",pesos(e.amount))
                .put("amountCentavos",e.amount)
                .put("note",cleanNote)
                .put("status",status)
        }

        val timelineArray=JSONArray()
        val todayArray=JSONArray()
        val yesterdayArray=JSONArray()
        allActivities.sortedByDescending {it.first}.take(80).forEach {(date,obj)->
            timelineArray.put(obj)
            if(date==today) todayArray.put(obj)
            if(date==yesterday) yesterdayArray.put(obj)
        }

        val todayActive = entries.filter { it.date == today && !it.isReversal && it.id !in reversedCashIds && !it.isWalletTransfer && !it.isSalaryRelated }
        val todayExpenses = todayActive.filter { !it.income }.sumOf { it.amount }
        val todayIncome = todayActive.filter { it.income }.sumOf { it.amount }
        val todayCashSummary = JSONObject()
            .put("expenseFormatted", formatPesos(todayExpenses)).put("expenseCentavos", todayExpenses)
            .put("incomeFormatted", formatPesos(todayIncome)).put("incomeCentavos", todayIncome)
            .put("description", "Full active cash ledger for today; excludes reversed records, internal wallet transfers and canonical salary entries. Loan payments are listed separately. Income does not cancel spending.")

        val plan=PaydayPlanner.calculate(profile,loans,policies,entries,today,savingsAccountNames)
        val categories=PaydayPlanner.categoryBreakdown(profile,entries,today)
        val categoryArray=JSONArray()
        categories.forEach {cat->
            categoryArray.put(JSONObject()
                .put("category",cat.category)
                .put("amountCentavos",cat.amount)
                .put("sharePercent",cat.sharePercent))
        }
        val allocationsArray=JSONArray()
        plan.allocations.forEach {alloc->
            val idx=loans.indexOfFirst {it.id==alloc.loanId}
            val alias=if(idx>=0)"L${idx+1}" else "UNKNOWN"
            allocationsArray.put(JSONObject()
                .put("loan",alias)
                .put("installmentNumber",alloc.installment+1)
                .put("dueDate",alloc.due.toString())
                .put("reservedCentavos",alloc.amount)
                .put("reserveType",if(alloc.sinking)"NEXT_CUTOFF_50PCT_SPLIT" else "CURRENT_CUTOFF_DUE"))
        }

        val baseSim=PaydayPlanner.simulatePayoff(loans,policies,profile,0L)
        val sim500=PaydayPlanner.simulatePayoff(loans,policies,profile,50_000L)
        val sim1000=PaydayPlanner.simulatePayoff(loans,policies,profile,100_000L)
        val targetLoan=PaydayPlanner.surplusTarget(loans,policies,profile.strategy)
        val targetAlias=targetLoan?.let {t->"L${loans.indexOfFirst {it.id==t.id}+1}"} ?: "NONE"

        val inCutoffActiveCash=entries.filter {!it.id.startsWith("undo_")&&it.id !in reversedCashIds&&!it.date.isBefore(profile.start)&&it.date.isBefore(profile.next)&&!it.date.isAfter(today)}
        val cutoffExtraIncome=inCutoffActiveCash.filter {it.income&&!it.isWalletTransfer&&it.channel !in savingsAccountNames}.sumOf {it.amount}
        val cutoffExpenses=inCutoffActiveCash.filter {!it.income&&!it.isWalletTransfer&&it.channel !in savingsAccountNames}.sumOf {it.amount}
        val cutoffSavingsDeposits=inCutoffActiveCash.filter {it.income&&it.channel in savingsAccountNames}.sumOf {it.amount}
        val cutoffSavingsWithdrawals=inCutoffActiveCash.filter {!it.income&&it.channel in savingsAccountNames}.sumOf {it.amount}

        val accountBalances=PaydayPlanner.accountBalances(accounts,entries,profile)
        val accountsArray=JSONArray()
        accounts.forEach {acc->
            val bal = accountBalances[acc.id] ?: accountBalances[acc.name] ?: 0L
            val isSalaryDest = profile.salary > 0L && (acc.id == profile.salaryAccountId || acc.name.equals(profile.salaryAccountId, ignoreCase = true))
            accountsArray.put(JSONObject()
                .put("name",acc.name)
                .put("icon",acc.icon)
                .put("isSavings",acc.isSavings)
                .put("isSalaryDestination",isSalaryDest)
                .put("balanceFormatted",formatPesos(bal))
                .put("balancePesos",pesos(bal))
                .put("balanceCentavos",bal))
        }
        val savingsVaultCentavos=PaydayPlanner.savingsBalance(entries,savingsAccountNames,profile,accounts)

        val calendarArray=JSONArray()
        val allUpcomingDues=loans.flatMapIndexed {i,loan->
            val alias="L${i+1}"
            val party=sanitizeText(loan.party,loans)
            loan.installments().filter {it.remaining>0}.map {inst->
                Triple(inst.due,inst,Pair(alias,party))
            }
        }.sortedBy {it.first}

        allUpcomingDues.take(30).forEach {(due,inst,loanInfo)->
            val (alias,party)=loanInfo
            val daysUntil=ChronoUnit.DAYS.between(today,due)
            calendarArray.put(JSONObject()
                .put("loan",alias)
                .put("party",party)
                .put("installmentNumber",inst.index+1)
                .put("dueDate",due.toString())
                .put("dayOfWeek",due.dayOfWeek.name)
                .put("amountFormatted",formatPesos(inst.remaining))
                .put("amountPesos",pesos(inst.remaining))
                .put("amountCentavos",inst.remaining)
                .put("daysUntil",daysUntil)
                .put("status",when {
                    daysUntil<0 -> "OVERDUE"
                    daysUntil==0L -> "DUE_TODAY"
                    daysUntil<=3L -> "DUE_WITHIN_3_DAYS"
                    daysUntil<=7L -> "DUE_SOON"
                    else -> "UPCOMING"
                }))
        }

        val paydaysArray=JSONArray()
        paydaysArray.put(JSONObject().put("date",profile.start.toString()).put("label","Current cutoff start"))
        paydaysArray.put(JSONObject().put("date",profile.next.toString()).put("label","Next payday").put("daysUntil",ChronoUnit.DAYS.between(today,profile.next).coerceAtLeast(0)))
        val cutoffSpan=ChronoUnit.DAYS.between(profile.start,profile.next).coerceAtLeast(1)
        paydaysArray.put(JSONObject().put("date",profile.next.plusDays(cutoffSpan).toString()).put("label","Projected next payday"))

        val receiptsArray=JSONArray()
        suggestions.forEach {s->
            receiptsArray.put(JSONObject()
                .put("id",s.id)
                .put("source",s.source)
                .put("amountCentavos",s.amount?:0L)
                .put("date",s.date?.toString()?:"UNKNOWN")
                .put("reference",redactSensitiveInNote(s.reference?:"",loans))
                .put("note",redactSensitiveInNote(s.note,loans)))
        }

        val chatArray=JSONArray()
        history.takeLast(6).forEach {turn->
            if(turn.question.isNotBlank()&&turn.answer.isNotBlank()&&!turn.isError) {
                chatArray.put(JSONObject()
                    .put("user",sanitizeText(turn.question,loans))
                    .put("assistant",turn.answer))
            }
        }

        return JSONObject()
            .put("todayCashSummary",todayCashSummary)
            .put("today",today.toString())
            .put("yesterday",yesterday.toString())
            .put("dayOfWeek",today.dayOfWeek.name)
            .put("command",sanitized)
            .put("recentChat",chatArray)
            .put("loans",snapshot)
            .put("accounts",accountsArray)
            .put("savingsVault",JSONObject()
                .put("totalFormatted",formatPesos(savingsVaultCentavos))
                .put("totalPesos",pesos(savingsVaultCentavos))
                .put("totalCentavos",savingsVaultCentavos)
                .put("savingsAccountNames",JSONArray(savingsAccountNames.toList()))
                .put("cutoffDepositsFormatted",formatPesos(cutoffSavingsDeposits))
                .put("cutoffDepositsCentavos",cutoffSavingsDeposits)
                .put("cutoffWithdrawalsFormatted",formatPesos(cutoffSavingsWithdrawals))
                .put("cutoffWithdrawalsCentavos",cutoffSavingsWithdrawals)
                .put("description","Protected savings vault - isolated from cutoff safe-to-spend allowance"))
            .put("calendar",calendarArray)
            .put("budget",JSONObject()
                .put("cutoffStart",profile.start.toString())
                .put("nextPayday",profile.next.toString())
                .put("daysUntilNextPayday",ChronoUnit.DAYS.between(today,profile.next).coerceAtLeast(0))
                .put("upcomingPaydays",paydaysArray)
                .put("openingSalaryFormatted",formatPesos(profile.salary))
                .put("openingSalaryPesos",pesos(profile.salary))
                .put("openingSalaryCentavos",profile.salary)
                .put("salaryWallet", accounts.find { it.id == profile.salaryAccountId || it.name.equals(profile.salaryAccountId, ignoreCase = true) }?.name ?: "Default wallet")
                .put("cutoffExtraIncomeFormatted",formatPesos(cutoffExtraIncome))
                .put("cutoffExtraIncomePesos",pesos(cutoffExtraIncome))
                .put("cutoffExtraIncomeCentavos",cutoffExtraIncome)
                .put("cutoffExpensesFormatted",formatPesos(cutoffExpenses))
                .put("cutoffExpensesPesos",pesos(cutoffExpenses))
                .put("cutoffExpensesCentavos",cutoffExpenses)
                .put("totalAvailableCashFormatted",formatPesos(plan.cash))
                .put("totalAvailableCashPesos",pesos(plan.cash))
                .put("cashCentavos",plan.cash)
                .put("safeToSpendFormatted",formatPesos(plan.safeToSpend))
                .put("safeToSpendPesos",pesos(plan.safeToSpend))
                .put("safeToSpendCentavos",plan.safeToSpend)
                .put("dailyAllowanceFormatted",formatPesos(plan.daily))
                .put("dailyAllowancePesos",pesos(plan.daily))
                .put("dailyAllowanceCentavos",plan.daily)
                .put("shortfallFormatted",formatPesos(plan.shortfall))
                .put("shortfallCentavos",plan.shortfall)
                .put("unreservedDuesCentavos",plan.unreservedDues)
                .put("essentialsTargetCentavos",profile.essentials)
                .put("essentialsReservedCentavos",plan.essentials)
                .put("emergencyTargetCentavos",profile.emergency)
                .put("emergencyReservedCentavos",plan.emergency)
                .put("strategy",profile.strategy.name)
                .put("priorityExtraPaymentLoan",targetAlias)
                .put("reservedAllocations",allocationsArray)
                .put("spendingByCategory",categoryArray))
            .put("payoffProjection",JSONObject()
                .put("totalDebtRemainingFormatted",formatPesos(baseSim.totalRemaining))
                .put("totalDebtRemainingCentavos",baseSim.totalRemaining)
                .put("scheduledDebtFreeDate",baseSim.scheduledDebtFreeDate?.toString()?:"NONE")
                .put("withExtra500PhpDate",sim500.simulatedDebtFreeDate?.toString()?:"NONE")
                .put("withExtra500PhpDaysSaved",sim500.daysSaved)
                .put("withExtra1000PhpDate",sim1000.simulatedDebtFreeDate?.toString()?:"NONE")
                .put("withExtra1000PhpDaysSaved",sim1000.daysSaved))
            .put("activities",JSONObject()
                .put("todayTransactions",todayArray)
                .put("yesterdayTransactions",yesterdayArray)
                .put("recentCashEntries",cashArray)
                .put("recentTimeline",timelineArray))
            .put("pendingReceipts",receiptsArray)
            .put("appFeatures",JSONObject()
                .put("receiptScanner","On-device camera/screenshot OCR in Receipts tab; zero cloud data upload")
                .put("interactiveTutorial","Guided tour with Petot spotlighting UI in Settings -> View app tutorial")
                .put("accountWalletsCrud","Manage accounts (Cash, GCash/Maya, Banks, Savings) in Settings -> Accounts & Wallets or Payday tab")
                .put("protectedSavingsVault","Savings accounts are excluded from safeToSpend allowance to keep reserves safe")
                .put("paymentCalendar","Monthly calendar grid of loan due dates & paydays with customizable loan/payday color markers")
                .put("statementAndReminders","Loan details has Statement of Account (copy/share) and borrower SMS reminder draft")
                .put("interestEstimator","Loan creation/edit form has built-in reducing-balance & flat-rate interest estimator")
                .put("ledgerReversals","Undo cash entries in Payday Activity; reverse loan payments in Loan Details -> Payment history")
                .put("payoffStrategies","Avalanche (highest APR first) and Snowball (smallest balance first) in Payday tab with payoff simulator")
                .put("appLock","Biometric/PIN device lock in Settings -> Privacy & backup")
                .put("backupRestore","Encrypted JSON export and restore in Settings -> Privacy & backup")
                .put("cloudSync","Firebase multi-device sync with Google account in Settings"))
    }
}
