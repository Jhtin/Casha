package com.example.casha.ui

import com.example.casha.ui.theme.FinanceDesign
import com.example.casha.ui.theme.ThemeMode
import com.example.casha.ui.theme.ThemePreference
import com.example.casha.ui.theme.rememberThemeMode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import android.Manifest
import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.casha.data.*
import com.example.casha.domain.*
import com.example.casha.reminders.BackgroundWork
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun nonnegative(text:String):Long?=if(text.trim() in listOf("0","0.0","0.00"))0 else parseMoney(text.trim())

@Composable private fun EntryField(value:String,change:(String)->Unit,label:String,money:Boolean=false,enabled:Boolean=true) {
    OutlinedTextField(value,change,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=enabled,shape=MaterialTheme.shapes.large,
        keyboardOptions=KeyboardOptions(keyboardType=if(money)KeyboardType.Decimal else KeyboardType.Text))
}

@Composable
fun PaydayScreen(loans:List<Loan>,finance:FinanceStore,modifier:Modifier=Modifier,onAssistant:()->Unit={},openLoan:(String)->Unit) {
    val revision by finance.changes.collectAsState()
    var edit by remember {mutableStateOf(false)}
    var rollover by remember {mutableStateOf(false)}
    var simulator by remember {mutableStateOf(false)}
    var cashDialog by remember {mutableStateOf(false)}
    var help by rememberSaveable {mutableStateOf(false)}
    var details by rememberSaveable {mutableStateOf(false)}
    var movements by rememberSaveable {mutableStateOf(false)}
    var allPriorities by rememberSaveable {mutableStateOf(false)}
    var activity by rememberSaveable {mutableStateOf(true)}
    var showHistory by rememberSaveable {mutableStateOf(false)}
    var historyQuery by rememberSaveable {mutableStateOf("")}
    var spendingBreakdown by rememberSaveable {mutableStateOf(false)}
    var strategyTools by rememberSaveable {mutableStateOf(false)}
    var status by remember {mutableStateOf<String?>(null)}
    var reverseCash by remember {mutableStateOf<CashEntry?>(null)}
    var editingCash by remember {mutableStateOf<CashEntry?>(null)}
    var manageAccountsDialog by remember {mutableStateOf(false)}
    val profile=remember(revision){runCatching {finance.profile()}.getOrNull()}
    val entries=remember(revision){runCatching {finance.cashEntries()}.getOrNull()}
    if(profile==null||entries==null) {Box(modifier.fillMaxSize().padding(20.dp)){NoticeCard("Budget unavailable","Saved budget data could not be read. It has not been overwritten.")};return}
    val defaultSalaryId=remember(revision){finance.defaultSalaryAccountId()}
    val savingsNames=remember(revision){finance.savingsAccountNames()}
    val moneyAccounts=remember(revision){finance.moneyAccounts()}
    val totalSavings=remember(entries,savingsNames,profile,moneyAccounts){PaydayPlanner.savingsBalance(entries,savingsNames,profile,moneyAccounts)}
    val paymentLinks=remember(revision){finance.allPaymentLinks()}
    val accountBalances=remember(entries,revision,profile,defaultSalaryId,paymentLinks,loans){
        PaydayPlanner.accountBalances(
            accounts = moneyAccounts,
            entries = entries,
            profile = profile,
            defaultSalaryAccountId = defaultSalaryId,
            paymentLinks = paymentLinks,
            loans = loans
        )
    }
    val policies=loans.associate {it.id to finance.policy(it.id)}
    val today=LocalDate.now()
    val plan=PaydayPlanner.calculate(profile,loans,policies,entries,today,savingsNames)
    val currentActivity=remember(entries,profile,today){cashActivityEntries(entries,profile,true,today=today)}
    val categories=remember(profile,entries,today){PaydayPlanner.categoryBreakdown(profile,entries,today)}
    val baseSim=remember(loans,policies,profile){PaydayPlanner.simulatePayoff(loans,policies,profile,0L)}
    val target=PaydayPlanner.surplusTarget(loans,policies,profile.strategy)
    val dateFormat=java.time.format.DateTimeFormatter.ofPattern("MMM d")
    val daysUntil=ChronoUnit.DAYS.between(today,profile.next).coerceAtLeast(0)
    val inWindow=entries.filter {!it.date.isBefore(profile.start)&&it.date.isBefore(profile.next)&&!it.date.isAfter(today)}
    val extraIncome=inWindow.filter {it.income&&!it.isWalletTransfer&&it.channel !in savingsNames}.sumOf {it.amount}
    val expenses=inWindow.filterNot {it.income||it.isWalletTransfer||it.channel in savingsNames}.sumOf {it.amount}
    val netLoanCash=plan.cash-profile.salary-extraIncome+expenses
    val dueNow=loans.filter {it.mode==LoanMode.I_OWE}.flatMap {loan->loan.installments().filter {it.remaining>0&&it.due.isBefore(profile.next)}.map {loan to it}}.sortedBy {it.second.due}
    val funding=cutoffFunding(profile,loans,entries,today,savingsNames)
    val currentBudget=finance.preferences.contains("profile")&&!today.isBefore(profile.start)&&today.isBefore(profile.next)
    val currentReserves=plan.allocations.filterNot {it.sinking}.sumOf {it.amount}
    val futureReserves=plan.allocations.filter {it.sinking}.sumOf {it.amount}
    LazyColumn(modifier.fillMaxSize().testTag("payday-list"),contentPadding=PaddingValues(FinanceDesign.PagePadding),verticalArrangement=Arrangement.spacedBy(28.dp)) {
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)){ScreenHeading("Payday strategist","${profile.start.format(dateFormat)} – ${profile.next.minusDays(1).format(dateFormat)}")};IconButton({edit=true},modifier=Modifier.semantics {contentDescription="Edit payday budget"}){PilotIcon("Settings")}}}
        if(!today.isBefore(profile.next))item {NoticeCard("Cutoff ended","Update your budget before relying on this allowance.","Roll over cutoff",{rollover=true})}
        if(plan.shortfall>0)item {Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text("Shortfall: ${money(plan.shortfall)}",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onErrorContainer);Text("Living needs, reserves or due payments are not fully covered.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)}
        }}
        item {SectionRegion("Payday overview") {
            SafeSpendCard(plan.safeToSpend,plan.daily,funding,currentBudget,"Next payday ${profile.next.format(dateFormat)} · ${daysUntil} ${if(daysUntil==1L)"day" else "days"} away")
            Surface(modifier=Modifier.fillMaxWidth().tutorialTarget("manage-accounts"),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            PilotIcon("Savings",Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
                            Text("Accounts & Savings",style=MaterialTheme.typography.titleSmall)
                        }
                        IconButton({manageAccountsDialog=true},modifier=Modifier.size(36.dp).semantics {contentDescription="Manage accounts"}){PilotIcon("Settings",Modifier.size(16.dp))}
                    }
                    if(totalSavings>0L) {
                        Surface(color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.45f),shape=RoundedCornerShape(8.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                Column {
                                    Text("Protected Savings Vault",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                    Text(money(totalSavings),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Surface(color=MaterialTheme.colorScheme.primary.copy(alpha=0.15f),shape=RoundedCornerShape(50)) {
                                    Text("Safe from spend",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(horizontal=8.dp,vertical=2.dp))
                                }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        moneyAccounts.forEach {acc->
                            val bal=accountBalances[acc.id]?:0L
                            val isSalaryDest=profile.salary>0L && (acc.id==(profile.salaryAccountId ?: defaultSalaryId) || acc.name.equals(profile.salaryAccountId, ignoreCase = true))
                            Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(8.dp),border=BorderStroke(1.dp,if(isSalaryDest)MaterialTheme.colorScheme.primary.copy(alpha=0.6f) else MaterialTheme.colorScheme.outlineVariant)) {
                                Row(Modifier.padding(horizontal=8.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                    PilotIcon(acc.icon,Modifier.size(13.dp),tint=if(isSalaryDest)MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                    Text(acc.name,style=MaterialTheme.typography.bodySmall)
                                    Text(money(bal),style=MaterialTheme.typography.labelSmall,color=if(bal<0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
            Button({cashDialog=true},modifier=Modifier.fillMaxWidth().heightIn(min=FinanceDesign.TouchTarget),shape=MaterialTheme.shapes.medium){PilotIcon("Plus",Modifier.size(16.dp));Spacer(Modifier.width(8.dp));Text("Log cash")}
        }}
        item {PaydayPanel("Income & available cash","Recorded money · before planned reserves") {
            Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                val salaryWalletName=moneyAccounts.find { it.id==(profile.salaryAccountId ?: defaultSalaryId) || it.name.equals(profile.salaryAccountId, ignoreCase = true) }?.name
                MoneyLine(if(salaryWalletName!=null && profile.salary>0L)"Opening cash ($salaryWalletName)" else "Opening cash",profile.salary)
                PaydayTotal("Current available cash",plan.cash)
                Disclosure("Cash movements",movements,{movements=!movements})
                if(movements) {
                    MoneyLine("Additional income",extraIncome)
                    MoneyLine("Cash expenses",-expenses)
                    MoneyLine("Net loan payments / receipts",netLoanCash)
                    Text("Opening cash includes salary and carryover. Reversals appear as opposite cash entries.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }}
        item {PaydayPanel("Upcoming obligations","Outstanding loan installments before ${profile.next.format(dateFormat)}") {
            PaydayTotal("Total due",dueNow.sumOf {it.second.remaining},"Scheduled obligations, not a new expense")
            if(dueNow.isEmpty())Text("No outstanding payments in this cutoff.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            (if(allPriorities)dueNow else dueNow.take(3)).forEach {(loan,inst)->
                val urgency=dueUrgencyColors(inst.due,inst.remaining)
                FinancialListRow(loan.party,inst.due.format(dateFormat),inst.remaining,urgency.label,urgency.accent,{openLoan(loan.id)})
            }
            if(dueNow.size>3)TextButton({allPriorities=!allPriorities},shape=MaterialTheme.shapes.small){Text(if(allPriorities)"Show fewer payments" else "See all ${dueNow.size} payments")}
        }}
        item {PaydayPanel("Allocation plan","Calculated reserves · no payments recorded") {
            Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                MoneyLine("Living needs",plan.essentials)
                MoneyLine("Emergency buffer",plan.emergency)
                MoneyLine("Loan payments reserved",currentReserves)
                if(futureReserves>0)MoneyLine("Next-cutoff split reserves",futureReserves)
                PaydayTotal("Remaining to spend",plan.safeToSpend,"Same amount as Safe to spend above")
                Disclosure("Strategy tools",strategyTools,{strategyTools=!strategyTools},modifier=Modifier.tutorialTarget("payoff-strategies"))
                if(strategyTools) {
                    Text("${profile.strategy.name.lowercase().replaceFirstChar {it.uppercase()}} strategy",style=MaterialTheme.typography.titleSmall)
                    if(target!=null&&plan.safeToSpend>0)TextButton({openLoan(target.id)},shape=MaterialTheme.shapes.small){
                        PilotIcon("Arrow",Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Review extra payment: ${target.party}")
                    }
                    if(baseSim.scheduledDebtFreeDate!=null) {
                        Text("Scheduled debt-free date: ${baseSim.scheduledDebtFreeDate}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({simulator=true},shape=MaterialTheme.shapes.small){
                            PilotIcon("Calculator",Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Payoff simulator")
                        }
                    }
                    if(plan.allocations.isNotEmpty()) {
                        Text("Planned payment reserves",style=MaterialTheme.typography.titleSmall)
                        plan.allocations.forEach {allocation->
                            val loan=loans.first {it.id==allocation.loanId}
                            FinancialListRow(loan.party,"${allocation.due.format(dateFormat)} · ${if(allocation.sinking)"50% split reserve" else "Current reserve"}",allocation.amount,status=if((policies[loan.id]?.risk?:1)>=2)"High priority" else null,statusColor=MaterialTheme.colorScheme.tertiary,onClick={openLoan(loan.id)})
                        }
                    }
                }
            }
        }}
        item {Disclosure("Budget details",details,{details=!details})
            if(details)Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Plan actions",style=MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({edit=true},shape=MaterialTheme.shapes.medium){
                        PilotIcon("Settings",Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Set budget")
                    }
                    OutlinedButton({rollover=true},shape=MaterialTheme.shapes.medium){
                        PilotIcon("Refresh",Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Rollover cutoff")
                    }
                }
                OutlinedButton({
                    val saved=JSONObject().put("start",profile.start).put("next",profile.next).put("cash",plan.cash).put("safeToSpend",plan.safeToSpend)
                        .put("allocations",JSONArray(plan.allocations.map {JSONObject().put("loanId",it.loanId).put("installment",it.installment).put("amount",it.amount).put("sinking",it.sinking)}))
                    status=if(finance.preferences.edit().putString("savedPlan",saved.toString()).commit())"Plan saved on this device. No payment was recorded." else "Could not save the plan."
                },shape=MaterialTheme.shapes.medium){
                    PilotIcon("Check",Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save allocation plan")
                }
            }
        }
        status?.let {m->item {NoticeCard("Budget update",m)}}
        item {Disclosure("Activity (${currentActivity.size})",activity,{activity=!activity},modifier=Modifier.tutorialTarget("cutoff-activity"))}
        if(activity) {
            item {
                Surface(
                    shape=MaterialTheme.shapes.large,
                    color=MaterialTheme.colorScheme.surface,
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal=12.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Recent activity",
                                style=MaterialTheme.typography.titleSmall,
                                fontWeight=FontWeight.SemiBold
                            )
                            Surface(
                                color=MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape=RoundedCornerShape(50),
                                border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f))
                            ) {
                                Text(
                                    if(currentActivity.isEmpty()) "No activity" else "Showing ${minOf(5,currentActivity.size)} of ${currentActivity.size}",
                                    modifier=Modifier.padding(horizontal=8.dp,vertical=2.dp),
                                    style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if(currentActivity.isEmpty()) {
                            Text(
                                "No income, expenses or reversals recorded yet.",
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier=Modifier.padding(vertical=8.dp)
                            )
                        } else {
                            Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                val topEntries=currentActivity.take(5)
                                topEntries.forEachIndexed {index,entry->
                                    CashActivityRow(entry,entries,finance,onEdit={editingCash=it}){reverseCash=it}
                                    if(index<topEntries.size-1) {
                                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f))

                        OutlinedButton(
                            onClick={historyQuery="";showHistory=true},
                            modifier=Modifier.fillMaxWidth().heightIn(min=42.dp),
                            enabled=entries.isNotEmpty(),
                            shape=MaterialTheme.shapes.medium
                        ) {
                            PilotIcon("History",Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("View full history (${entries.size})",style=MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if(categories.isNotEmpty())item {
                Disclosure("Spending breakdown",spendingBreakdown,{spendingBreakdown=!spendingBreakdown})
                if(spendingBreakdown)Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("This payday only",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    categories.forEach {cat->Column(Modifier.padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        MoneyLine(cat.category,cat.amount)
                        LinearProgressIndicator(progress={cat.sharePercent/100f},modifier=Modifier.fillMaxWidth().height(4.dp),color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.surfaceContainerHighest)
                    }}
                }
            }
        }
        item {TextButton({help=true},shape=MaterialTheme.shapes.small){
            PilotIcon("Info",Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("How this plan works")
        }}
    }
    if(help)PilotModal(onDismissRequest={help=false},title={Text("About your payday plan")},category="Payday guide",icon="Payday",accent=MaterialTheme.colorScheme.secondary,text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Reserves are a plan, not payments. Record actual payments in the loan ledger.")
        Text("Safe-to-spend percentage compares remaining spending money with opening cash plus net extra income and loan collections this cutoff. Reversals are accounted for. Green: above 50%; yellow: 20–50%; red: below 20%. Unset or expired budgets have no percentage.")
        Text("Opening cash already includes your salary and carryover. Log only additional income or spending to avoid counting it twice.")
        Text("Living needs and your emergency buffer come first. The planner then reserves money for loan installments. Optional split reserves cover 50% of eligible next-cutoff installments.")
        Text("Extra-payment suggestions use your selected ${profile.strategy.name.lowercase()} strategy. They do not automatically spend your daily allowance.")
        Text("Budget settings, cash logs and saved plans stay on this device, isolated by account. Only loans and payments currently sync. Enter carried cash when setting the next cutoff.")
    }},confirmButton={Button({help=false},shape=MaterialTheme.shapes.medium){Text("Got it")}})
    if(edit)ProfileDialog(profile,finance,{edit=false}) {edit=false;status="Budget updated."}
    if(rollover)RolloverDialog(profile,finance,plan.safeToSpend,{rollover=false}) {if(finance.saveProfile(it)){rollover=false;status="Started next cutoff (${it.start} to ${it.next})."}else status="Could not save next cutoff."}
    if(simulator)DebtFreeSimulatorDialog(loans,finance,profile,{simulator=false})
    if(cashDialog)CashDialog({cashDialog=false},finance) {if(finance.addCash(it)){cashDialog=false;activity=true;status=null;if(it.date.isBefore(profile.start)||!it.date.isBefore(profile.next)){historyQuery=it.date.toString();showHistory=true}}else status="Could not save cash entry."}
    if(showHistory)CashActivityHistory(entries,profile,finance,historyQuery,{showHistory=false},onEdit={editingCash=it}){reverseCash=it}
    if(manageAccountsDialog)ManageAccountsDialog(finance, loans){manageAccountsDialog=false}
    editingCash?.let {entry->
        CashDialog(
            dismiss={editingCash=null},
            finance=finance,
            existingEntry=entry,
            onDelete={
                val ok=finance.deleteCash(entry.id)
                editingCash=null
                status=if(ok)"Entry deleted." else "Could not delete entry."
            },
            save={updated->
                val ok=finance.updateCash(updated)
                editingCash=null
                status=if(ok)"Entry updated." else "Could not update entry."
            }
        )
    }
    reverseCash?.let {entry->PilotModal(onDismissRequest={reverseCash=null},title={Text("Reverse cash entry?")},category="Cash audit",icon="Settings",accent=MaterialTheme.colorScheme.error,text={Text("${if(entry.isWalletTransfer || entry.id.startsWith("atm_fee_")) "Reverse both wallet movements and the withdrawal fee together." else "Add an opposite entry for ${money(entry.amount)} dated today."} The original stays in history.")},confirmButton={Button({status=if(finance.reverseCash(entry.id))"Reversal saved." else "Could not reverse this entry.";reverseCash=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),shape=MaterialTheme.shapes.medium){Text("Confirm reversal")}},dismissButton={TextButton({reverseCash=null},shape=MaterialTheme.shapes.small){Text("Cancel")}})}
}

@Composable internal fun DebtFreeSimulatorDialog(loans:List<Loan>,finance:FinanceStore,profile:PaydayProfile,dismiss:()->Unit) {
    val policies=remember(loans) {loans.associate {it.id to finance.policy(it.id)}}
    var extraText by rememberSaveable {mutableStateOf("1000")}
    var strategy by rememberSaveable {mutableStateOf(profile.strategy)}
    val extraCentavos=nonnegative(extraText) ?: 0L
    val sim=remember(loans,policies,profile,extraCentavos,strategy) {
        PaydayPlanner.simulatePayoff(loans,policies,profile,extraCentavos,strategy)
    }
    PilotModal(
        onDismissRequest=dismiss,
        title={Text("Debt-free payoff simulator")},
        category="Payoff projection",
        icon="Loans",
        accent=MaterialTheme.colorScheme.primary,
        text={
            Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Compare payoff dates with extra payments each cutoff. This projection assumes scheduled payments remain affordable; it does not model new lender fees or record payments.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                EntryField(extraText,{extraText=it},"Extra payment per cutoff (PHP)",true)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    listOf("0" to "₱0","500" to "+₱500","1000" to "+₱1,000","2500" to "+₱2,500","5000" to "+₱5,000").forEach {(valStr,label)->
                        FilterChip(extraText.trim()==valStr,{extraText=valStr},label={Text(label)})
                    }
                }
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Payoff strategy",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        FilterChip(strategy==DebtStrategy.AVALANCHE,{strategy=DebtStrategy.AVALANCHE},label={Text("Avalanche (high interest)")})
                        FilterChip(strategy==DebtStrategy.SNOWBALL,{strategy=DebtStrategy.SNOWBALL},label={Text("Snowball (small balance)")})
                    }
                }
                val simAccent=MaterialTheme.colorScheme.primary
                Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,simAccent.copy(alpha=0.35f)),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("SIMULATION RESULT",style=MaterialTheme.typography.labelSmall,color=simAccent)
                        MoneyLine("Total debt remaining",sim.totalRemaining)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Regular debt-free date",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${sim.scheduledDebtFreeDate ?: "—"}",style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurface)
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Simulated debt-free date",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${sim.simulatedDebtFreeDate ?: "—"}",style=MaterialTheme.typography.titleMedium,color=simAccent)
                        }
                        if(sim.daysSaved>0) {
                            Surface(color=MaterialTheme.colorScheme.secondary.copy(alpha=0.16f),shape=MaterialTheme.shapes.small) {
                                Text("Estimated ${sim.daysSaved} days earlier (${sim.cutoffsSaved} cutoffs)",Modifier.padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.secondary)
                            }
                        } else {
                            Text("Add an extra amount above to see how many days you can save.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        sim.firstClearedParty?.let {party->
                            Text("First loan prioritized: $party",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton={Button(dismiss,shape=MaterialTheme.shapes.medium){Text("Done")}}
    )
}

@Composable private fun RolloverDialog(profile:PaydayProfile,finance:FinanceStore,safeToSpend:Long,dismiss:()->Unit,save:(PaydayProfile)->Unit) {
    val accounts = remember { finance.moneyAccounts() }
    val defaultSalaryId = remember { finance.defaultSalaryAccountId() }
    val defaultRolled=remember(profile) {PaydayPlanner.rolloverProfile(profile,0L)}
    var start by rememberSaveable {mutableStateOf(defaultRolled.start.toString())}
    var next by rememberSaveable {mutableStateOf(defaultRolled.next.toString())}
    var baseSalary by rememberSaveable {mutableStateOf(java.math.BigDecimal.valueOf(profile.salary,2).toPlainString())}
    var selectedSalaryAccountId by rememberSaveable {
        mutableStateOf(
            profile.salaryAccountId?.takeIf { id -> accounts.any { it.id == id || it.name.equals(id, ignoreCase = true) } }
                ?: defaultSalaryId
        )
    }
    var includeCarryover by rememberSaveable {mutableStateOf(safeToSpend>0)}
    var error by remember {mutableStateOf<String?>(null)}
    val parsedBase=nonnegative(baseSalary) ?: 0L
    val totalOpening=(parsedBase + if(includeCarryover)safeToSpend.coerceAtLeast(0) else 0L).coerceAtMost(Loan.MAX_AMOUNT)
    PilotModal(
        onDismissRequest=dismiss,
        title={Text("Next cutoff rollover")},
        category="Cutoff transition",
        icon="Calendar",
        accent=MaterialTheme.colorScheme.secondary,
        text={
            Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Advance your budget window to the next cutoff and optionally carry over your unspent Safe-to-Spend balance.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                DateEntryField(start,{start=it;error=null},"New cutoff start (YYYY-MM-DD)")
                DateEntryField(next,{next=it;error=null},"New next payday (YYYY-MM-DD)")
                EntryField(baseSalary,{baseSalary=it;error=null},"Base salary for new cutoff (PHP)",true)
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier=Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Text("Store salary in wallet",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(selectedSalaryAccountId==defaultSalaryId) {
                            Surface(
                                color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.5f),
                                shape=RoundedCornerShape(50)
                            ) {
                                Text(
                                    "Default",
                                    style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.primary,
                                    modifier=Modifier.padding(horizontal=8.dp,vertical=2.dp)
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        accounts.forEach {acc->
                            FilterChip(
                                selected=selectedSalaryAccountId==acc.id,
                                onClick={selectedSalaryAccountId=acc.id},
                                label={Text(acc.name)},
                                leadingIcon={PilotIcon(acc.icon,Modifier.size(16.dp))}
                            )
                        }
                    }
                }
                if(safeToSpend>0) {
                    ConsentToggle("Include remaining Safe-to-Spend (${money(safeToSpend)}) as carryover",includeCarryover,{includeCarryover=it})
                }
                Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Text("New opening cash",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(money(totalOpening),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            }
        },
        confirmButton={Button({
            val s=nonnegative(baseSalary)
            val updated=runCatching {
                requireNotNull(s)
                val carry=if(includeCarryover)safeToSpend.coerceAtLeast(0) else 0L
                val newStart = LocalDate.parse(start)
                val newNext = LocalDate.parse(next)

                // Archive the current outgoing cutoff
                val snapshot = HistoricalCutoff(
                    id = "cutoff_${profile.start}_${profile.next}",
                    start = profile.start,
                    next = profile.next,
                    baseSalary = profile.salary,
                    carryover = profile.carryover,
                    essentials = profile.essentials,
                    emergency = profile.emergency,
                    strategy = profile.strategy,
                    salaryAccountId = profile.salaryAccountId,
                    finalSafeToSpend = safeToSpend
                )
                finance.archiveCutoff(snapshot)

                // Deposit canonical salary into the chosen wallet for the new cutoff
                if (s > 0L) {
                    val targetAcc = accounts.firstOrNull { it.id == selectedSalaryAccountId } ?: accounts.first()
                    val salaryEntryId = "salary_cutoff_${newStart}_${targetAcc.id}"
                    val salaryEntry = CashEntry(
                        id = salaryEntryId,
                        income = true,
                        amount = s,
                        date = newStart,
                        note = "Salary · Cutoff ${newStart} to ${newNext}",
                        channel = targetAcc.name
                    )
                    finance.addCash(salaryEntry)
                }

                PaydayProfile(
                    start = newStart,
                    next = newNext,
                    salary = s,
                    essentials = profile.essentials,
                    emergency = profile.emergency,
                    strategy = profile.strategy,
                    salaryAccountId = selectedSalaryAccountId,
                    carryover = carry
                )
            }.getOrNull()
            if(updated==null) error="Use valid dates (1–62 day cutoff) and a valid base salary." else save(updated)
        },shape=MaterialTheme.shapes.medium){Text("Start new cutoff")}},
        dismissButton={TextButton(dismiss,shape=MaterialTheme.shapes.small){Text("Cancel")}}
    )
}

@Composable internal fun PaydayMetric(label:String,amount:Long,icon:String,accent:Color,modifier:Modifier=Modifier) {
    Surface(modifier,color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(money(amount),style=MaterialTheme.typography.titleMedium)}
    }
}

@Composable private fun ProfileDialog(profile:PaydayProfile,finance:FinanceStore,dismiss:()->Unit,save:(PaydayProfile)->Unit) {
    val accounts = remember { finance.moneyAccounts() }
    val defaultSalaryId = remember { finance.defaultSalaryAccountId() }
    var selectedSalaryAccountId by rememberSaveable {
        mutableStateOf(
            profile.salaryAccountId?.takeIf { id -> accounts.any { it.id == id || it.name.equals(id, ignoreCase = true) } }
                ?: defaultSalaryId
        )
    }
    var setAsDefault by rememberSaveable { mutableStateOf(false) }
    var adjustRecordedDeposit by rememberSaveable { mutableStateOf(false) }
    var start by rememberSaveable {mutableStateOf(profile.start.toString())};var next by rememberSaveable {mutableStateOf(profile.next.toString())}
    var salary by rememberSaveable {mutableStateOf(java.math.BigDecimal.valueOf(profile.salary,2).toPlainString())}
    var essentials by rememberSaveable {mutableStateOf(java.math.BigDecimal.valueOf(profile.essentials,2).toPlainString())}
    var emergency by rememberSaveable {mutableStateOf(java.math.BigDecimal.valueOf(profile.emergency,2).toPlainString())}
    var strategy by rememberSaveable {mutableStateOf(profile.strategy)};var error by remember {mutableStateOf<String?>(null)}
    PilotModal(onDismissRequest=dismiss,title={Text("Payday budget")},category="Cutoff planner",icon="Payday",accent=MaterialTheme.colorScheme.secondary,text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            DateEntryField(start,{start=it},"Cutoff start (YYYY-MM-DD)")
            DateEntryField(next,{next=it},"Next payday (YYYY-MM-DD)")
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Quick-set next payday",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Frequency.entries.forEach {f->
                        AssistChip(
                            onClick={runCatching {next=PaydayPlanner.nextPayday(LocalDate.parse(start),f).toString();error=null}.onFailure {error="Enter a valid start date first."}},
                            label={Text("Next: ${f.name.lowercase()}")}
                        )
                    }
                }
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Text("Opening cash includes salary and carryover at the cutoff start, BEFORE the payments and spending recorded in this window.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            EntryField(salary,{salary=it},"Opening cash before spending (PHP)",true)
            val parsedSalary = nonnegative(salary)
            if (parsedSalary != null && parsedSalary != profile.salary) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { adjustRecordedDeposit = !adjustRecordedDeposit }
                ) {
                    Checkbox(checked = adjustRecordedDeposit, onCheckedChange = { adjustRecordedDeposit = it })
                    Spacer(Modifier.width(4.dp))
                    Text("Record salary adjustment in wallet (${money(profile.salary)} → ${money(parsedSalary)})", style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier=Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.SpaceBetween,
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Text("Store salary in wallet",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(selectedSalaryAccountId==defaultSalaryId) {
                        Surface(
                            color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.5f),
                            shape=RoundedCornerShape(50)
                        ) {
                            Text(
                                "Default",
                                style=MaterialTheme.typography.labelSmall,
                                color=MaterialTheme.colorScheme.primary,
                                modifier=Modifier.padding(horizontal=8.dp,vertical=2.dp)
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    accounts.forEach {acc->
                        FilterChip(
                            selected=selectedSalaryAccountId==acc.id,
                            onClick={selectedSalaryAccountId=acc.id},
                            label={Text(acc.name)},
                            leadingIcon={PilotIcon(acc.icon,Modifier.size(16.dp))}
                        )
                    }
                }
                if(selectedSalaryAccountId!=defaultSalaryId) {
                    Row(
                        verticalAlignment=Alignment.CenterVertically,
                        modifier=Modifier.clickable {setAsDefault=!setAsDefault}
                    ) {
                        Checkbox(checked=setAsDefault,onCheckedChange={setAsDefault=it})
                        Spacer(Modifier.width(4.dp))
                        Text("Set as default salary wallet",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            EntryField(essentials,{essentials=it},"Living needs for this cutoff (PHP)",true)
            EntryField(emergency,{emergency=it},"Emergency buffer (PHP)",true)
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Extra payment strategy",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    DebtStrategy.entries.forEach {s->FilterChip(strategy==s,{strategy=s},label={Text(s.name.lowercase())})}
                }
            }
            error?.let {m->Text(m,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    },confirmButton={Button({
        val p=runCatching {
            PaydayProfile(
                LocalDate.parse(start),
                LocalDate.parse(next),
                requireNotNull(nonnegative(salary)),
                requireNotNull(nonnegative(essentials)),
                requireNotNull(nonnegative(emergency)),
                strategy,
                salaryAccountId=selectedSalaryAccountId,
                carryover=profile.carryover
            )
        }.getOrNull()
        if(p==null)error="Use valid dates, a 1–62 day cutoff and nonnegative amounts with at most 2 decimals."
        else {
            if(setAsDefault) {
                finance.setDefaultSalaryAccountId(selectedSalaryAccountId)
            }
            val ok = finance.updatePaydayProfile(p, adjustRecordedDeposit = adjustRecordedDeposit)
            if (ok) save(p) else error = "Could not save budget."
        }
    },shape=MaterialTheme.shapes.medium) {Text("Save budget")}},dismissButton={TextButton(dismiss,shape=MaterialTheme.shapes.small){Text("Cancel")}})
}

@Composable internal fun CashDialog(
    dismiss:()->Unit,
    finance:FinanceStore,
    proposal:AiProposal?=null,
    existingEntry:CashEntry?=null,
    onDelete:(()->Unit)?=null,
    saveError:String?=null,
    save:(CashEntry)->Unit
) {
    val isEdit=existingEntry!=null
    val accounts=remember(finance.changes.collectAsState().value){finance.moneyAccounts()}
    var selectedAccount by rememberSaveable {mutableStateOf(existingEntry?.channel ?: proposal?.channel?.ifBlank { null } ?: accounts.firstOrNull {!it.isSavings}?.name?:MoneyAccount.DEFAULT_CASH)}
    var income by rememberSaveable {mutableStateOf(existingEntry?.income ?: (proposal?.kind!="EXPENSE"))}
    var amount by rememberSaveable {mutableStateOf(existingEntry?.let {java.math.BigDecimal.valueOf(it.amount,2).toPlainString()} ?: proposal?.amount?.let {java.math.BigDecimal.valueOf(it,2).toPlainString()}?:"")}
    val extractedCategory=remember(existingEntry) {
        val noteText=existingEntry?.note?:""
        val prefix=noteText.substringBefore(" · ").trim()
        if(prefix in listOf("Food","Transport","Bills","Shopping")) prefix else ""
    }
    var category by rememberSaveable {mutableStateOf(extractedCategory)}
    var note by rememberSaveable {
        mutableStateOf(
            if(existingEntry!=null) {
                val noteText=existingEntry.note
                if(extractedCategory.isNotEmpty()&&noteText.contains(" · ")) noteText.substringAfter(" · ").trim() else noteText
            } else proposal?.explanation?.take(200)?:""
        )
    }
    var date by rememberSaveable {mutableStateOf(existingEntry?.date?.toString() ?: LocalDate.now().toString())}
    var error by remember {mutableStateOf<String?>(null)}
    val id=rememberSaveable {existingEntry?.id ?: proposal?.id ?: UUID.randomUUID().toString()}
    var busy by remember {mutableStateOf(false)}
    val keyboard=LocalSoftwareKeyboardController.current
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    var pendingCameraPath by rememberSaveable {mutableStateOf<String?>(null)}
    var scanNote by remember {mutableStateOf<String?>(null)}
    var confirmDelete by remember {mutableStateOf(false)}
    fun scan(uri:Uri,cleanup:java.io.File?=null) {
        busy=true;error=null;keyboard?.hide()
        scope.launch {
            val result=withContext(Dispatchers.IO) {
                try {runCatching {ReceiptScanner.scan(context,uri,finance.scope)}} finally {cleanup?.delete()}
            }
            result.onSuccess {receipt->
                receipt.amount?.let {amount=java.math.BigDecimal.valueOf(it,2).toPlainString()}
                receipt.date?.takeUnless {it.isAfter(LocalDate.now())}?.let {date=it.toString()}
                note=receipt.reference?.let {"Receipt · Ref $it"}?.take(160)?:if(income)"Receipt income" else "Receipt expense"
                scanNote="Receipt scanned. Check the amount and date before saving. ${receipt.note}"
                // Cash records do not have a receipt attachment field. Remove the temporary OCR copy.
                receipt.receipt?.let {runCatching {ReceiptScanner.file(context,it,finance.scope).delete()}}
            }.onFailure {error=it.message?:"Could not read this receipt. You can enter it manually."}
            busy=false
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri->if(uri!=null)scan(uri)}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) {captured->
        val file=pendingCameraPath?.let {java.io.File(it)};pendingCameraPath=null
        if(captured&&file!=null&&file.exists())scan(FileProvider.getUriForFile(context,"${context.packageName}.files",file),file) else file?.delete()
    }
    val modalAccent=when {
        income -> MaterialTheme.colorScheme.secondary
        category.isNotBlank() -> categoryColor(category)
        else -> MaterialTheme.colorScheme.primary
    }
    PilotModal(
        onDismissRequest={if(!busy){keyboard?.hide();dismiss()}},
        title={Text(if(isEdit)"Edit cash entry" else if(income)"Log income" else "Review expense")},
        category=if(isEdit)"Cash record" else if(income)"Extra income" else "Cash expense",
        icon=if(isEdit)"Edit" else "Payday",
        accent=modalAccent,
        text={
            Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                SegmentedToggle(
                    options=listOf("Income","Expense"),
                    selectedIndex=if(income) 0 else 1,
                    onSelect={income=(it==0)},
                    modifier=Modifier.fillMaxWidth()
                )
                EntryField(amount,{amount=it;error=null},"Amount (PHP)",true,enabled=!busy)
                (error?:saveError)?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                if(!isEdit) {
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Read a receipt",style=MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({keyboard?.hide();picker.launch("image/*")},enabled=!busy,modifier=Modifier.weight(1f)){
                                PilotIcon("Download",Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Import image")
                            }
                            OutlinedButton({
                                runCatching {ReceiptScanner.createCaptureTarget(context,finance.scope)}.onSuccess {(file,uri)->
                                    pendingCameraPath=file.path
                                    runCatching {camera.launch(uri)}.onFailure {file.delete();pendingCameraPath=null;error="No camera available. Import an image instead."}
                                }.onFailure {error="Could not open camera."}
                            },enabled=!busy,modifier=Modifier.weight(1f)){
                                PilotIcon("Camera",Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Take photo")
                            }
                        }
                        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                        scanNote?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                        Text("On-device scanning. Nothing is saved until you confirm the cash entry.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if(!income) {
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Category",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            listOf("Food","Transport","Bills","Shopping").forEach {label->
                                val chipColor=categoryColor(label)
                                FilterChip(
                                    selected=(category==label),
                                    onClick={category=if(category==label)"" else label},
                                    label={Text(label)},
                                    leadingIcon={Box(Modifier.size(8.dp).clip(CircleShape).background(chipColor))}
                                )
                            }
                        }
                    }
                }
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Account / Wallet",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        accounts.forEach {acc->
                            FilterChip(
                                selected=selectedAccount==acc.name,
                                onClick={selectedAccount=acc.name},
                                label={Text(acc.name)},
                                leadingIcon={PilotIcon(acc.icon,Modifier.size(16.dp))},
                                enabled=!busy
                            )
                        }
                    }
                    val isSavingsAccount=accounts.find {it.name==selectedAccount}?.isSavings==true
                    if(isSavingsAccount) {
                        Surface(color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.45f),shape=RoundedCornerShape(8.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=0.3f))) {
                            Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                PilotIcon("Savings",Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary)
                                Text(if(income)"Savings deposit: Stays protected in your savings vault and is NOT added to Safe to Spend." else "Savings withdrawal: Drawn from your savings vault and does not reduce your Safe to Spend allowance.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
                DateEntryField(date,{date=it},"Date (YYYY-MM-DD)",enabled=!busy);EntryField(note,{note=it.take(180)},"Note",enabled=!busy)
            }
        },
        confirmButton={
            Button(
                onClick={
                    val value=parseMoney(amount);val day=runCatching {LocalDate.parse(date)}.getOrNull()
                    if(value==null||day==null||day.isAfter(LocalDate.now())||day.year<1900)error="Enter a positive amount and a valid date no later than today."
                    else {
                        val combinedNote=if(!income&&category.isNotEmpty()) listOf(category,note.trim()).filter {it.isNotEmpty()}.joinToString(" · ").take(200) else note.trim().take(200)
                        keyboard?.hide()
                        save(CashEntry(id,income,value,day,combinedNote,channel=selectedAccount))
                    }
                },
                enabled=!busy,
                shape=MaterialTheme.shapes.medium
            ) {
                PilotIcon("Check",Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if(busy)"Reading receipt…" else if(isEdit)"Save changes" else "Save cash entry")
            }
        },
        dismissButton={
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                if(isEdit&&onDelete!=null) {
                    TextButton(
                        onClick={confirmDelete=true},
                        enabled=!busy,
                        colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),
                        shape=MaterialTheme.shapes.small
                    ) {
                        PilotIcon("Delete",Modifier.size(15.dp),tint=MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
                TextButton({keyboard?.hide();dismiss()},enabled=!busy,shape=MaterialTheme.shapes.small){Text("Cancel")}
            }
        }
    )
    if(confirmDelete) {
        PilotModal(
            onDismissRequest={confirmDelete=false},
            title={Text("Delete cash entry?")},
            category="Cash ledger",
            icon="Delete",
            accent=MaterialTheme.colorScheme.error,
            text={Text("Remove this ${money(existingEntry?.amount?:0)} entry permanently from your records.")},
            confirmButton={
                Button(
                    onClick={
                        confirmDelete=false
                        onDelete?.invoke()
                    },
                    colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),
                    shape=MaterialTheme.shapes.medium
                ) {
                    Text("Confirm delete")
                }
            },
            dismissButton={
                TextButton(onClick={confirmDelete=false},shape=MaterialTheme.shapes.small){Text("Cancel")}
            }
        )
    }
}

@Composable
fun AutomationScreen(store:LoanRepository,loans:List<Loan>,finance:FinanceStore,modifier:Modifier=Modifier) {
    var assistantTab by rememberSaveable {mutableStateOf(0)}
    val context=LocalContext.current;val scope=rememberCoroutineScope();val revision by finance.changes.collectAsState()
    var status by remember {mutableStateOf<String?>(null)};var busy by remember {mutableStateOf(false)}
    var selected by remember {mutableStateOf<PaymentSuggestion?>(null)}
    var pendingCameraPath by rememberSaveable {mutableStateOf<String?>(null)}
    val suggestions=remember(revision){runCatching {finance.suggestions()}.getOrDefault(emptyList())}
    fun processReceiptUri(uri:Uri,cleanupFile:java.io.File?=null) {
        busy=true
        scope.launch {
            val result=withContext(Dispatchers.IO) {
                try {
                    runCatching {ReceiptScanner.scan(context,uri,finance.scope)}
                } finally {
                    cleanupFile?.delete()
                }
            }
            result.onSuccess {suggestion->
                val added=finance.addSuggestion(suggestion)
                selected=suggestion
                status=if(added)"Receipt scanned. Review details to record." else "Inbox is full, or this receipt was already scanned. You can still review it now."
            }.onFailure {status=it.message?:"Could not scan this image."}
            busy=false
        }
    }
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri->
        if(uri!=null) processReceiptUri(uri)
    }
    val cameraLauncher=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) {captured->
        val path=pendingCameraPath
        pendingCameraPath=null
        val file=path?.let {java.io.File(it)}
        if(captured&&file!=null&&file.exists()&&file.length()>0L) {
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
            processReceiptUri(uri,file)
        } else {
            file?.delete()
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text("Petot",style=MaterialTheme.typography.headlineMedium)
                Text("AI budgeting & on-device receipt OCR",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }

        }
        Box(Modifier.padding(horizontal=20.dp,vertical=4.dp)) {
            SegmentedToggle(
                options=listOf("AI chat","Receipts${if(suggestions.isEmpty()) "" else " (${suggestions.size})"}"),
                selectedIndex=assistantTab,
                onSelect={assistantTab=it}
            )
        }
        // Keep chat state alive while visiting the receipt inbox; neither tab sends requests automatically.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(if(assistantTab==0)Modifier.fillMaxSize() else Modifier.size(0.dp)) {GeminiPanel(store,loans,finance)}
            if(assistantTab==1)LazyColumn(Modifier.fillMaxSize().testTag("assist-list"),contentPadding=PaddingValues(horizontal=FinanceDesign.PagePadding,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                item {
                    HeroCard(accent=MaterialTheme.colorScheme.primary) {
                                                Text("Scan a receipt",style=MaterialTheme.typography.titleMedium)
                        Text("Use your camera or a saved screenshot. Text recognition stays on this device.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick={
                                    runCatching {
                                        val (file,uri)=ReceiptScanner.createCaptureTarget(context,finance.scope)
                                        pendingCameraPath=file.absolutePath
                                        cameraLauncher.launch(uri)
                                    }.onFailure {status="Camera is unavailable on this device."}
                                },
                                enabled=!busy,
                                modifier=Modifier.fillMaxWidth().heightIn(min=FinanceDesign.TouchTarget),
                                shape=MaterialTheme.shapes.medium,
                                colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary)
                            ) {
                                PilotIcon("Camera",Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if(busy)"Scanning…" else "Use camera")
                            }
                            OutlinedButton(
                                onClick={imagePicker.launch("image/*")},
                                enabled=!busy,
                                modifier=Modifier.fillMaxWidth().heightIn(min=FinanceDesign.TouchTarget),
                                shape=MaterialTheme.shapes.medium,
                                border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
                                colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.primary)
                            ) {
                                PilotIcon("Receipt",Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if(busy)"Scanning…" else "Scan receipt screenshot")
                            }
                        }
                    }
                }
                status?.let {m->item {NoticeCard("Receipt scan",m)}}
                if(suggestions.isEmpty())item {NoticeCard("No pending receipts","Captured receipt photos and screenshots appear here if you close the review dialog without saving.")}
                if(suggestions.isNotEmpty())item {
                    Surface(
                        modifier=Modifier.fillMaxWidth(),
                        shape=MaterialTheme.shapes.large,
                        color=MaterialTheme.colorScheme.surface,
                        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            suggestions.forEachIndexed {index,suggestion->
                                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                        Text(suggestion.source.uppercase(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                        Text("${suggestion.date?:"Date needs review"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(suggestion.amount?.let {money(it)}?:"Amount needs review",style=MaterialTheme.typography.titleLarge)
                                    Text("Ref: ${suggestion.reference?:"Not detected"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(suggestion.note,style=MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        FilledTonalButton({selected=suggestion},shape=MaterialTheme.shapes.medium) {
                                            PilotIcon("Check",Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Review payment")
                                        }
                                        TextButton({finance.dismissSuggestion(suggestion.id)},shape=MaterialTheme.shapes.small) {
                                            PilotIcon("Close",Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Dismiss")
                                        }
                                    }
                                }
                                if(index < suggestions.lastIndex) {
                                    HorizontalDivider(
                                        color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f),
                                        modifier=Modifier.padding(horizontal=16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let {suggestion->SuggestionDialog(store,loans,finance,suggestion,{selected=null}) {status=it;selected=null}}
}

@Composable internal fun SuggestionDialog(store:LoanRepository,snapshot:List<Loan>,finance:FinanceStore,suggestion:PaymentSuggestion,dismiss:()->Unit,initialLoanId:String?=null,done:(String)->Unit) {
    val initial=remember(suggestion.id){snapshot.toList()};val context=LocalContext.current;val coroutine=rememberCoroutineScope()
    val activeLoans=remember(initial){initial.filter {it.remaining>0 || it.payments.any {payment->payment.id=="suggestion_${suggestion.id}"}}}
    var loanId by rememberSaveable(suggestion.id) {mutableStateOf(initialLoanId ?: activeLoans.singleOrNull()?.id)}
    var amount by rememberSaveable(suggestion.id) {mutableStateOf(suggestion.amount?.let {java.math.BigDecimal.valueOf(it,2).toPlainString()}?:"")}
    var date by rememberSaveable(suggestion.id) {mutableStateOf((suggestion.date?:LocalDate.now()).toString())}
    var reference by rememberSaveable(suggestion.id) {mutableStateOf(suggestion.reference?:"")};var error by remember {mutableStateOf<String?>(null)}
    var duplicateApproved by remember {mutableStateOf(false)};var busy by remember {mutableStateOf(false)}
    val accounts=remember(finance){finance.moneyAccounts().filter {!it.isArchived && !it.isSavings}}
    var accountId by rememberSaveable(suggestion.id){mutableStateOf(accounts.firstOrNull {it.id=="acc_cash"}?.id ?: accounts.firstOrNull()?.id)}
    val account=accounts.find {it.id==accountId}
    val selected=initial.find {it.id==loanId};val parsedAmount=parseMoney(amount);val parsedDate=runCatching {LocalDate.parse(date)}.getOrNull()
    val alreadyRecorded=selected?.payments?.any {it.id=="suggestion_${suggestion.id}"}==true
    val possibleDuplicate=(!alreadyRecorded&&reference.isNotBlank()&&finance.referenceUsed(reference)) || selected?.payments?.any {it.id!="suggestion_${suggestion.id}"&&it.amount==parsedAmount&&it.date==parsedDate}==true
    PilotModal(onDismissRequest={if(!busy)dismiss()},title={Text("Review detected payment")},category="Receipt OCR",icon="Camera",accent=MaterialTheme.colorScheme.primary,text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Choose a loan payment or an expense, and the wallet used. The receipt date determines which payday budget changes.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(activeLoans.isEmpty()) Text("No loans with an outstanding balance were found.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            else FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                activeLoans.forEach {loan->FilterChip(loanId==loan.id,{loanId=loan.id;duplicateApproved=false},label={Text("${loan.party} · ${money(loan.remaining)}")})}
            }
            Text("Wallet used / received into",style=MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {accounts.forEach {wallet->FilterChip(accountId==wallet.id,{if(!busy)accountId=wallet.id},label={Text(wallet.name)})}}
            EntryField(amount,{amount=it;duplicateApproved=false},"Amount (PHP)",true)
            DateEntryField(date,{date=it;duplicateApproved=false},"Paid on (YYYY-MM-DD)")
            EntryField(reference,{reference=it.take(100);duplicateApproved=false},"Reference (optional)")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                suggestion.receipt?.let {receipt->OutlinedButton({runCatching {
                    val file=ReceiptScanner.file(context,receipt,finance.scope);val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure {error="No image viewer is available."}},modifier=Modifier.weight(1f),shape=MaterialTheme.shapes.medium){Text("View receipt")}}
                OutlinedButton(
                    enabled=!busy,
                    shape=MaterialTheme.shapes.medium,
                    onClick={
                        if(account==null||parsedAmount==null||parsedDate==null||parsedDate.isAfter(LocalDate.now())||parsedDate.year<1900) {
                            error="Enter a valid amount and past/current date to log as an expense."
                        } else {
                            val noteText=if(reference.isNotBlank())"Receipt · Ref ${reference.trim()}" else "Receipt expense"
                            val entry=CashEntry("receipt_${suggestion.id}",false,parsedAmount,parsedDate,noteText.take(200),account.name)
                            busy=true
                            coroutine.launch {
                                val result=withContext(Dispatchers.IO){runCatching {finance.recordReceiptExpense(suggestion,entry,reference.trim().ifBlank {null})}.getOrDefault(false)}
                                busy=false
                                if(result)done("Expense saved: ${money(parsedAmount)} · ${account.name} · $parsedDate. See Payday / Activity / All history.")
                                else error="Could not save the expense, or this receipt was already recorded. Check Activity before retrying."
                            }
                        }
                    },
                    modifier=Modifier.weight(1f)
                ) {Text("Log as expense")}
            }
            if(possibleDuplicate) {
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Possible duplicate: a matching reference or amount/date already exists.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    ConsentToggle("I checked; this is a different payment",duplicateApproved,{duplicateApproved=it})
                }
            }
            error?.let {m->Text(m,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    },confirmButton={Button(enabled=!busy&&(!possibleDuplicate||duplicateApproved),shape=MaterialTheme.shapes.medium,onClick={
        val next=selected?.installments()?.firstOrNull {it.remaining>0}
        if(account==null||selected==null||(!alreadyRecorded&&next==null)||parsedAmount==null||(!alreadyRecorded&&parsedAmount>(next?.remaining?:0))||parsedDate==null||parsedDate.isAfter(LocalDate.now())||parsedDate.year<1900)error="Choose a loan, a valid past/current date and an amount no greater than its next outstanding installment."
        else {busy=true;coroutine.launch {val result=withContext(Dispatchers.IO){runCatching {
            com.example.casha.data.recordReviewedReceiptPayment(store,finance,initial,suggestion,selected.id,parsedAmount,parsedDate,account,reference.trim().ifBlank {null})
            "Payment recorded: ${money(parsedAmount)} · ${account.name} · $parsedDate. See the loan payment history and wallet balance."
        }};result.onSuccess(done).onFailure {error=it.message?:"Could not save this payment."};busy=false}}
    }){
        PilotIcon("Check",Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if(busy)"Saving…" else "Record reviewed payment")
    }},dismissButton={TextButton(dismiss,enabled=!busy,shape=MaterialTheme.shapes.small){Text("Cancel")}})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun GeminiPanel(store:LoanRepository,loans:List<Loan>,finance:FinanceStore) {
    val context=LocalContext.current;val coroutine=rememberCoroutineScope();val keys=remember(finance.scope){GeminiKeyStore(context,finance.scope)}
    var command by rememberSaveable {mutableStateOf("")};var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)};var proposal by remember {mutableStateOf<AiProposal?>(null)}
    var manual by remember {mutableStateOf<AiProposal?>(null)}
    var duplicateApproved by remember {mutableStateOf(false)}
    var snapshot by remember {mutableStateOf<List<Loan>>(emptyList())}
    val voice=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->
        if(result.resultCode==Activity.RESULT_OK)command=result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.take(2000)?:command
    }
    val keyboard=LocalSoftwareKeyboardController.current
    val chatScroll=rememberLazyListState()
    val chatStore=remember(finance.scope){AssistantChatStore(context,finance.scope)}
    var messages by remember(finance.scope){mutableStateOf(chatStore.load())}
    // messages are persisted locally via chatStore
    var reviewable by remember {mutableStateOf<AiProposal?>(null)}
    var reviewedId by remember {mutableStateOf<String?>(null)}
    var clearChat by remember {mutableStateOf(false)}
    var archivedSessions by remember(finance.scope){mutableStateOf(chatStore.loadArchivedSessions())}
    var showHistoryModal by remember {mutableStateOf(false)}
    val settingsRevision by finance.changes.collectAsState()
    val companionOptions by rememberCompanionOptions()
    val isPaidTier=remember(settingsRevision,finance.scope){finance.flag("ai_paid_tier",false)}
    val configured=remember(settingsRevision,finance.scope) {keys.exists()&&finance.flag("aiConsent")&&(isPaidTier||finance.flag("freeKeyConfirmed"))}
    var assistantLanguage by remember(settingsRevision,finance.scope) {mutableStateOf(finance.assistantLanguage())}
    fun sendMessage() {
        if(busy||command.isBlank())return
        val text=command.trim();command="";busy=true;error=null;snapshot=loans.toList();keyboard?.hide()
        val pendingTurn=AssistantChatTurn(question=text,answer="",timestamp=System.currentTimeMillis())
        val updated=(messages+pendingTurn).takeLast(100);messages=updated;reviewable=null
        coroutine.launch {
            val historyTurns = messages.filter { it.answer.isNotBlank() && !it.isError }.takeLast(6)
            val result=withContext(Dispatchers.IO){runCatching {GeminiClient(finance,keys).propose(text,snapshot,historyTurns)}}
            result.onSuccess {p->
                val finished=pendingTurn.copy(answer=p.explanation,isError=false)
                val finalHistory=messages.dropLast(1)+finished;messages=finalHistory
                withContext(Dispatchers.IO){chatStore.save(finalHistory)}
                reviewable=if(p.kind=="ADVICE")null else p;duplicateApproved=false
            }.onFailure {
                error=it.message?:"Could not connect. Your records have not changed."
                val errorTurn=pendingTurn.copy(answer="I couldn’t complete that request. Your records have not changed.",isError=true)
                val finalHistory=messages.dropLast(1)+errorTurn;messages=finalHistory
                withContext(Dispatchers.IO){chatStore.save(finalHistory)}
                command=text
            }
            busy=false
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(messages.size,messages.lastOrNull()?.answer,busy,error,imeVisible) {if(messages.isNotEmpty())chatScroll.animateScrollToItem(messages.size-1+if(error!=null)1 else 0)}
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = if(configured) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = if(configured) (if(isPaidTier) "Key configured (Paid)" else "Key configured (Free)") else "Setup needed",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if(configured) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                val totalSavedCount = messages.size
                if(totalSavedCount > 0 || archivedSessions.isNotEmpty()){
                    Surface(
                        onClick = { showHistoryModal = true },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PilotIcon("History", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = if(totalSavedCount > 0) "$totalSavedCount saved" else "History",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                Surface(
                    onClick = {
                        val next = if(assistantLanguage == AssistantLanguage.ENGLISH) AssistantLanguage.TAGALOG else AssistantLanguage.ENGLISH
                        if(finance.setAssistantLanguage(next)) assistantLanguage = next
                    },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = if(assistantLanguage == AssistantLanguage.ENGLISH) "English" else "Tagalog",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if(messages.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                FilledTonalButton(
                    onClick = { clearChat = true },
                    enabled = !busy,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    PilotIcon("Plus", Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "New chat",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat-list"),state=chatScroll,contentPadding=PaddingValues(horizontal=FinanceDesign.PagePadding,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if(messages.isEmpty()) {
                item {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    PetotCharacter(PetotMood.HELLO,companionOptions.second)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Your financial assistant",style=MaterialTheme.typography.titleMedium)
                    Text("Ask Petot about your budget or draft a transaction in English or Taglish. Requests use your current ledger and recent chat context.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }}
                item {Column {
                    val starterPrompts=if(assistantLanguage==AssistantLanguage.ENGLISH) listOf(
                        "Plan my payday" to "How can I prioritize my next loan payments?",
                        "Safe-to-spend check" to "How much is safe to spend until my next payday?",
                        "Calendar & dues" to "What are my upcoming loan dues on the calendar?",
                        "Protected savings vault" to "How much is in my protected savings vault?",
                        "Log an expense" to "I spent 350 pesos on groceries from E-Wallet",
                        "Record extra income" to "I received 2500 pesos extra income into my Bank"
                    ) else listOf(
                        "Planuhin ang sahod" to "Paano ko dapat unahin ang bayad sa mga loans ko sa susunod na sahod?",
                        "Safe-to-spend check" to "Magkano pa ang ligtas kong gastusin bago ang susunod na sahod?",
                        "Calendar at bayarin" to "Ano-ano ang mga nakatakdang bayarin ko sa calendar?",
                        "Protected savings" to "Magkano na ang nakatago sa aking protected savings vault?",
                        "Magtala ng gastos" to "Gumastos ako ng 350 pesos para sa groceries gamit ang E-Wallet",
                        "Magtala ng kita" to "May pumasok na 2500 pesos na extra income sa aking Bank"
                    )
                    starterPrompts.forEach {(title,prompt)->
                        TextButton({command=prompt},modifier=Modifier.fillMaxWidth().heightIn(min=FinanceDesign.TouchTarget),contentPadding=PaddingValues(vertical=12.dp),shape=MaterialTheme.shapes.small) {
                            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);PilotIcon("Arrow",Modifier.size(16.dp))
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    }
                }}
                if(!configured)item {NoticeCard("Gemini API setup","Connect your own eligible free-tier key in Settings. Manual tools work without AI.")}
            }
            items(messages.size) {index->
                val msg=messages[index]
                val prevMsg=if(index>0)messages[index-1] else null
                val showDateDivider=prevMsg==null||(
                    java.time.Instant.ofEpochMilli(prevMsg.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate() !=
                    java.time.Instant.ofEpochMilli(msg.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                )
                val timeStr=java.time.Instant.ofEpochMilli(msg.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(showDateDivider) {
                        Box(Modifier.fillMaxWidth().padding(vertical=4.dp),contentAlignment=Alignment.Center) {
                            Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=RoundedCornerShape(12.dp)) {
                                val instant=java.time.Instant.ofEpochMilli(msg.timestamp)
                                val date=instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                                val today=java.time.LocalDate.now()
                                val label=when {
                                    date==today -> "Today"
                                    date==today.minusDays(1) -> "Yesterday"
                                    date.year==today.year -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d"))
                                    else -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"))
                                }
                                Text(label,modifier=Modifier.padding(horizontal=12.dp,vertical=3.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    Surface(color=MaterialTheme.colorScheme.primaryContainer,contentColor=MaterialTheme.colorScheme.onPrimaryContainer,shape=RoundedCornerShape(10.dp,10.dp,4.dp,10.dp),modifier=Modifier.widthIn(max=300.dp)) {
                        Column(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(msg.question,style=MaterialTheme.typography.bodyMedium)
                            Text(timeStr,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.7f),modifier=Modifier.align(Alignment.End))
                        }
                    }
                }
                Surface(
                    modifier=Modifier.fillMaxWidth(),
                    shape=MaterialTheme.shapes.medium,
                    color=MaterialTheme.colorScheme.surface,
                    border=BorderStroke(1.dp,if(msg.isError)MaterialTheme.colorScheme.error.copy(alpha=0.5f) else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            PetotCharacter(if(msg.answer.isBlank()&&busy)PetotMood.THINKING else if(msg.isError)PetotMood.CARE else PetotMood.CHEER,companionOptions.second&&index==messages.lastIndex,Modifier.size(30.dp))
                            Text("PETOT · GEMINI",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.weight(1f))
                            Text(timeStr,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(msg.answer.isBlank()&&busy) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
                                Text("Analyzing ledger…",style=MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(msg.answer,style=MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if(index==messages.lastIndex)reviewable?.let {p->
                            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if(reviewedId==p.id)"Record saved" else "Action ready for review",style=MaterialTheme.typography.titleSmall)
                                    Text("${p.kind.lowercase().replace('_',' ')} · ${money(p.amount)}${if(p.kind in listOf("INCOME","EXPENSE") && p.channel.isNotBlank()) " · ${p.channel}" else ""}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if(reviewedId!=p.id) {
                                    FilledTonalButton({proposal=p},shape=MaterialTheme.shapes.small) {
                                        PilotIcon("Check",Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Review proposal")
                                    }
                                }
                            }
                        }
                    }
                }
            }}
            error?.let {message->item {NoticeCard("Assistant notice",message)}}
        }
        Surface(color=MaterialTheme.colorScheme.surface) {
            Column {
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(command,{command=it.take(2000)},modifier=Modifier.fillMaxWidth().testTag("chat-composer").tutorialTarget("chat-composer"),label={Text(assistantLanguage.composerLabel)},placeholder={Text(assistantLanguage.composerPlaceholder)},shape=MaterialTheme.shapes.medium,maxLines=4,
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={sendMessage()}),
                        leadingIcon={IconButton({runCatching {voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,assistantLanguage.voiceLocale))}.onFailure {error="Voice input is unavailable on this device."}},enabled=!busy,modifier=Modifier.semantics {contentDescription="Voice input"}){PilotIcon("Voice")}},
                        trailingIcon={FilledIconButton({sendMessage()},enabled=!busy&&command.isNotBlank(),modifier=Modifier.semantics {contentDescription="Ask Gemini"}){PilotIcon("Send",Modifier.size(18.dp))}})
                    Text("Review every action · 10 requests/day · Avoid sensitive details",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }
    if(clearChat)PilotModal(
        onDismissRequest={clearChat=false},
        title={Text("Start a new chat?")},
        category="Assistant session",
        icon="AI",
        accent=MaterialTheme.colorScheme.primary,
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Archive your current ${messages.size} message(s) to Chat History so you can review them anytime, or clear them completely.")
                Text("Your ledger, loans, and accounts remain untouched.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton={
            Button({
                coroutine.launch {
                    withContext(Dispatchers.IO){chatStore.archiveCurrentSession()}
                    messages=emptyList()
                    archivedSessions=chatStore.loadArchivedSessions()
                    reviewable=null
                    reviewedId=null
                    command=""
                    error=null
                    clearChat=false
                }
            },shape=MaterialTheme.shapes.medium){
                Text("Archive & start new")
            }
        },
        dismissButton={
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton({
                    coroutine.launch {
                        withContext(Dispatchers.IO){chatStore.clear()}
                        messages=emptyList()
                        reviewable=null
                        reviewedId=null
                        command=""
                        error=null
                        clearChat=false
                    }
                },shape=MaterialTheme.shapes.small){
                    Text("Clear completely",color=MaterialTheme.colorScheme.error)
                }
                TextButton({clearChat=false},shape=MaterialTheme.shapes.small){Text("Cancel")}
            }
        }
    )

    if(showHistoryModal) {
        var historySearch by remember {mutableStateOf("")}
        var selectedTab by remember {mutableStateOf(0)}
        val filteredMessages = remember(messages, historySearch) {
            if(historySearch.isBlank()) messages
            else messages.filter {
                it.question.contains(historySearch, ignoreCase = true) ||
                it.answer.contains(historySearch, ignoreCase = true)
            }
        }
        val filteredSessions = remember(archivedSessions, historySearch) {
            if(historySearch.isBlank()) archivedSessions
            else archivedSessions.filter { session ->
                session.title.contains(historySearch, ignoreCase = true) ||
                session.turns.any { it.question.contains(historySearch, ignoreCase = true) || it.answer.contains(historySearch, ignoreCase = true) }
            }
        }

        PilotModal(
            onDismissRequest={showHistoryModal=false},
            title={Text("Chat history")},
            category="Petot",
            icon="History",
            accent=MaterialTheme.colorScheme.primary,
            text={
                Column(
                    modifier=Modifier.heightIn(max=480.dp).fillMaxWidth(),
                    verticalArrangement=Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value=historySearch,
                        onValueChange={historySearch=it},
                        modifier=Modifier.fillMaxWidth(),
                        placeholder={Text("Search past questions or answers…",style=MaterialTheme.typography.bodySmall)},
                        leadingIcon={PilotIcon("History",Modifier.size(16.dp))},
                        trailingIcon={
                            if(historySearch.isNotEmpty()) {
                                IconButton({historySearch=""}){PilotIcon("Close",Modifier.size(14.dp))}
                            }
                        },
                        shape=MaterialTheme.shapes.small,
                        singleLine=true
                    )

                    if(archivedSessions.isNotEmpty()) {
                        Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected=selectedTab==0,onClick={selectedTab=0},label={Text("Active (${messages.size})")})
                            FilterChip(selected=selectedTab==1,onClick={selectedTab=1},label={Text("Past sessions (${archivedSessions.size})")})
                        }
                    }

                    if(selectedTab==0) {
                        if(messages.isNotEmpty()) {
                            Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                Text("${filteredMessages.size} of ${messages.size} message(s)",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick={showHistoryModal=false;coroutine.launch{chatScroll.animateScrollToItem(0)}},contentPadding=PaddingValues(horizontal=8.dp,vertical=2.dp)) {
                                        Text("Top",style=MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick={showHistoryModal=false;coroutine.launch{chatScroll.animateScrollToItem(messages.size-1)}},contentPadding=PaddingValues(horizontal=8.dp,vertical=2.dp)) {
                                        Text("Latest",style=MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        if(filteredMessages.isEmpty()) {
                            Box(modifier=Modifier.fillMaxWidth().padding(vertical=24.dp),contentAlignment=Alignment.Center) {
                                Text(
                                    if(historySearch.isNotBlank()) "No matching messages found." else "No saved messages in this chat.",
                                    style=MaterialTheme.typography.bodySmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(modifier=Modifier.weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(filteredMessages) {_, msg->
                                    val originalIndex=messages.indexOf(msg).coerceAtLeast(0)
                                    val instant=java.time.Instant.ofEpochMilli(msg.timestamp)
                                    val date=instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                                    val time=instant.atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                                    val dateStr=when(date) {
                                        java.time.LocalDate.now() -> "Today"
                                        java.time.LocalDate.now().minusDays(1) -> "Yesterday"
                                        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
                                    }

                                    Surface(
                                        color=MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape=MaterialTheme.shapes.medium,
                                        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
                                        modifier=Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                            Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                                Text("$dateStr · $time",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                                TextButton(
                                                    onClick={
                                                        showHistoryModal=false
                                                        coroutine.launch{chatScroll.animateScrollToItem(originalIndex)}
                                                    },
                                                    contentPadding=PaddingValues(horizontal=6.dp,vertical=2.dp)
                                                ) {
                                                    Text("Jump to message",style=MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            Text(text=msg.question,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
                                            if(msg.answer.isNotBlank()) {
                                                Text(
                                                    text=if(msg.answer.length>140) msg.answer.take(140)+"…" else msg.answer,
                                                    style=MaterialTheme.typography.bodySmall,
                                                    color=MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        if(filteredSessions.isEmpty()) {
                            Box(modifier=Modifier.fillMaxWidth().padding(vertical=24.dp),contentAlignment=Alignment.Center) {
                                Text(
                                    if(historySearch.isNotBlank()) "No matching archived sessions." else "No archived sessions yet.",
                                    style=MaterialTheme.typography.bodySmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(modifier=Modifier.weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                items(filteredSessions) {session->
                                    val instant=java.time.Instant.ofEpochMilli(session.timestamp)
                                    val date=instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                                    val time=instant.atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                                    val dateStr=when(date) {
                                        java.time.LocalDate.now() -> "Today"
                                        java.time.LocalDate.now().minusDays(1) -> "Yesterday"
                                        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
                                    }

                                    Surface(
                                        color=MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape=MaterialTheme.shapes.medium,
                                        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
                                        modifier=Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                            Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                                Text("$dateStr · $time",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                                Text("${session.turns.size} messages",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Text(session.title,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
                                            Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick={
                                                        coroutine.launch {
                                                            withContext(Dispatchers.IO){chatStore.deleteArchivedSession(session.id)}
                                                            archivedSessions=chatStore.loadArchivedSessions()
                                                        }
                                                    },
                                                    modifier=Modifier.size(32.dp)
                                                ) {
                                                    PilotIcon("Trash",Modifier.size(15.dp),tint=MaterialTheme.colorScheme.error)
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                FilledTonalButton(
                                                    onClick={
                                                        coroutine.launch {
                                                            val restored=withContext(Dispatchers.IO){chatStore.restoreSession(session)}
                                                            messages=restored
                                                            archivedSessions=chatStore.loadArchivedSessions()
                                                            showHistoryModal=false
                                                        }
                                                    },
                                                    shape=MaterialTheme.shapes.small,
                                                    contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp)
                                                ) {
                                                    Text("Open chat",style=MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton={
                Button(onClick={showHistoryModal=false},shape=MaterialTheme.shapes.medium){Text("Close")}
            },
            dismissButton={
                if(messages.isNotEmpty()) {
                    TextButton(onClick={showHistoryModal=false;clearChat=true},shape=MaterialTheme.shapes.small){Text("New chat options")}
                }
            }
        )
    }
    proposal?.let {p->
        val possibleDuplicate=p.kind=="PAYMENT"&&p.target?.payments?.any {it.amount==p.amount&&it.date==LocalDate.now()}==true
        PilotModal(onDismissRequest={if(!busy)proposal=null},title={Text(if(p.kind=="ADVICE")"Gemini response" else "Review AI proposal")},category="Petot",icon="AI",accent=MaterialTheme.colorScheme.primary,text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(p.explanation,style=MaterialTheme.typography.bodyMedium)
        if(p.kind!="ADVICE") {
            val aiAccent=MaterialTheme.colorScheme.primary
            Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.large,border=BorderStroke(1.dp,aiAccent.copy(alpha=0.35f)),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("${p.kind.replace('_',' ')} · ${money(p.amount)}",style=MaterialTheme.typography.titleMedium,color=aiAccent)
                    if(p.kind in listOf("INCOME","EXPENSE")) {
                        val isSavings=finance.savingsAccountNames().contains(p.channel)
                        Text("Account: ${p.channel}${if(isSavings) " · Protected Savings Vault" else ""}",style=MaterialTheme.typography.bodySmall,color=if(isSavings) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    p.target?.let {Text("Loan: ${it.party}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface)}
                    p.newLoan?.let {Text("${it.party} · ${it.mode}\n${it.count} installments · ${it.frequency}\nFirst due ${it.firstDue}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
            }
        }
        Text("Nothing is saved until you confirm. Known whole loan names are replaced with aliases before the request.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(possibleDuplicate) {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("Possible duplicate: a payment with this amount is already recorded today.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                ConsentToggle("I checked; this is a different payment",duplicateApproved,{duplicateApproved=it})
            }
        }
        if(p.kind!="ADVICE")OutlinedButton({manual=p;proposal=null;reviewable=null;error=null},enabled=!busy,modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium){
            PilotIcon("Edit",Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Edit in manual form")
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }},confirmButton={if(p.kind!="ADVICE")Button(enabled=!busy&&(!possibleDuplicate||duplicateApproved),shape=MaterialTheme.shapes.medium,onClick={busy=true;coroutine.launch {
        val result=withContext(Dispatchers.IO){runCatching {
            val current=store.load();check(current==snapshot){"Records changed. Ask again to review an updated proposal."}
            when(p.kind) {
                "INCOME","EXPENSE" -> check(finance.addCash(CashEntry(p.id,p.kind=="INCOME",p.amount,LocalDate.now(),p.explanation.take(200),p.channel)))
                "CREATE_LOAN" -> check(store.save(current+requireNotNull(p.newLoan),current))
                "PAYMENT" -> {val loan=requireNotNull(p.target);val next=loan.installments().first {it.remaining>0};val updated=loan.pay(next.index,p.amount,"ai_${p.id}",LocalDate.now());check(store.save(current.map {if(it.id==loan.id)updated else it},current))}
                else -> error("Unsupported action")
            }
        }};result.onSuccess {reviewedId=p.id;proposal=null;command="";error=null}.onFailure {error=it.message?:"The proposal could not be applied."};busy=false
    }}) {
        PilotIcon("Check",Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if(busy)"Saving…" else "Confirm and save")
    }},dismissButton={TextButton({proposal=null},enabled=!busy,shape=MaterialTheme.shapes.small){Text(if(p.kind=="ADVICE")"Close" else "Cancel")}})}
    manual?.let {p->when(p.kind) {
        "INCOME","EXPENSE" -> CashDialog({manual=null},finance,p) {entry->if(finance.addCash(entry)){manual=null;command="";error=null}else error="Could not save the cash entry."}
        "PAYMENT" -> SuggestionDialog(store,snapshot,finance,PaymentSuggestion("ai_${p.id}",p.amount,LocalDate.now(),null,"Gemini proposal"),{manual=null},p.target?.id) {manual=null;command="";error=null}
        "CREATE_LOAN" -> p.newLoan?.let {draft->AddLoanDialog(draft.mode,busy,{if(!busy)manual=null},draft,error,true) {edited->
            busy=true;coroutine.launch {val result=withContext(Dispatchers.IO){runCatching {val current=store.load();check(current==snapshot);check(store.save(current+edited,current))}}
                result.onSuccess {manual=null;command="";error=null}.onFailure {error="Records changed or saving failed. Review a new proposal before retrying."};busy=false}
        }}
    }}
}

@Composable
fun SettingsScreen(store:LoanRepository,finance:FinanceStore,modifier:Modifier=Modifier,onStartTutorial:(()->Unit)?=null,accountContent:@Composable ()->Unit={}) {
    val context=LocalContext.current;val coroutine=rememberCoroutineScope();val revision by finance.changes.collectAsState()
    var expandedSection by rememberSaveable {mutableStateOf<String?>(null)}
    fun toggleSection(name:String){expandedSection=if(expandedSection==name)null else name}
    val companionOptions by rememberCompanionOptions()
    var petotPreview by remember { mutableStateOf<PetotReaction?>(null) }
    var previewMood by remember {mutableStateOf(PetotMood.HELLO)}
    petotPreview?.let { shown -> PetotPopup(shown, companionOptions.second) { if(petotPreview?.eventId == shown.eventId) petotPreview = null } }
    val companionSound by rememberCompanionSound()
    val themeMode by rememberThemeMode()
    val keys=remember(finance.scope){GeminiKeyStore(context,finance.scope)}
    var keyText by remember {mutableStateOf("")};var status by remember {mutableStateOf<String?>(null)}
    var confirmDeleteKey by remember {mutableStateOf(false)}
    var reminders by remember(revision){mutableStateOf(finance.flag("reminders"))}
    var consent by remember(revision){mutableStateOf(finance.flag("aiConsent"))}
    var freeKey by remember(revision){mutableStateOf(finance.flag("freeKeyConfirmed"))}
    var isPaidTier by remember(revision){mutableStateOf(finance.flag("ai_paid_tier",false))}
    var assistantLanguage by remember(revision){mutableStateOf(finance.assistantLanguage())}
    var lock by remember {mutableStateOf(context.getSharedPreferences("app_privacy",Context.MODE_PRIVATE).getBoolean("appLock",false))}
    var manageAccounts by remember {mutableStateOf(false)}
    val defaultSalaryId=remember(revision){finance.defaultSalaryAccountId()}
    val profile=remember(revision){runCatching {finance.profile()}.getOrNull()}
    val moneyAccounts=remember(revision){finance.moneyAccounts()}
    val entries=remember(revision){runCatching {finance.cashEntries()}.getOrDefault(emptyList())}
    val savingsNames=remember(revision){finance.savingsAccountNames()}
    val totalSavings=remember(entries,savingsNames,profile,moneyAccounts){PaydayPlanner.savingsBalance(entries,savingsNames,profile,moneyAccounts)}
    val storeRevision by store.changes.collectAsState()
    val loans = remember(storeRevision) { runCatching { store.load() }.getOrDefault(emptyList()) }
    val paymentLinks=remember(revision){finance.allPaymentLinks()}
    val accountBalances=remember(entries,revision,profile,defaultSalaryId,paymentLinks,loans){
        PaydayPlanner.accountBalances(
            accounts = moneyAccounts,
            entries = entries,
            profile = profile,
            defaultSalaryAccountId = defaultSalaryId,
            paymentLinks = paymentLinks,
            loans = loans
        )
    }
    val unresolvedIntents = remember(revision, storeRevision) { finance.unresolvedPaymentIntents(store.load()) }
    val hasSavedKey=remember(revision,finance.scope){keys.exists()}
    val maskedKey=remember(revision,finance.scope){keys.maskedKey()}
    var backup by remember {mutableStateOf<JSONObject?>(null)}
    var restoring by remember {mutableStateOf(false)}
    var showTutorial by rememberSaveable {mutableStateOf(false)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted){finance.setFlag("reminders",true);reminders=true;BackgroundWork.initialize(context);BackgroundWork.checkReminders(context)}
        else status="Notifications were not allowed. You can still see due dates in Loans."
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->if(uri!=null)coroutine.launch {
        val result=withContext(Dispatchers.IO){runCatching {val body=PortableBackup.encode(store,finance)
            context.contentResolver.openOutputStream(uri)?.use {it.write(body.toString(2).toByteArray())}?:error("No output stream")}}
        status=if(result.isSuccess)"Export saved. It includes financial/contact data, but no API key or receipt images. Keep it private." else result.exceptionOrNull()?.message ?: "Export failed; existing records are unchanged."
    }}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri->if(uri!=null)coroutine.launch {
        val result=withContext(Dispatchers.IO){runCatching {
            val text=context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {reader->
                val buffer=CharArray(1024);val out=StringBuilder();while(true){val n=reader.read(buffer);if(n<0)break;require(out.length+n<=5_000_000);out.append(buffer,0,n)};out.toString()
            }?:error("Unreadable backup")
            val data=JSONObject(text);PortableBackup.validate(data,finance);data
        }}
        result.onSuccess {backup=it}.onFailure {status="Backup is invalid or belongs to a different account/device ledger. No records were changed."}
    }}
    Box(modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().testTag("settings-list"),contentPadding=PaddingValues(horizontal=FinanceDesign.PagePadding,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {ScreenHeading("Settings","Tap a section to view its options.")}
        if (unresolvedIntents.isNotEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().testTag("unresolved-intents-card")
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PilotIcon("Warning", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            Text(
                                "Unresolved payment intents (${unresolvedIntents.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            "These payments were submitted but not confirmed in your loan ledger. If a transaction was aborted or failed, tap Discard to restore your wallet balance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        unresolvedIntents.forEach { intent ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${intent.accountName} · ${money(intent.amountCentavos)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "Payment: ${intent.paymentId.take(12)}… · ${intent.date}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val result = finance.cancelPendingPaymentIntent(intent.intentId, store)
                                            status = when (result) {
                                                CancelIntentResult.CANCELLED -> "Unconfirmed intent discarded. Wallet balance restored."
                                                CancelIntentResult.PROMOTED -> "Payment was confirmed in loan ledger and linked."
                                                CancelIntentResult.BLOCKED_UNREADY -> "Cloud sync is still initializing. Please wait until records finish syncing."
                                                CancelIntentResult.BLOCKED_CONFLICT -> "This loan has a sync conflict from another device. Resolve conflict first."
                                                CancelIntentResult.BLOCKED_EMPTY -> "Cannot discard while loans are empty. Sync with cloud to verify records."
                                                CancelIntentResult.NOT_FOUND -> "Intent not found."
                                            }
                                        },
                                        shape = MaterialTheme.shapes.small,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Discard", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {SettingsSection("Appearance","Theme: ${themeMode.label}",expandedSection=="appearance",{toggleSection("appearance")},icon="Appearance") {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                listOf(ThemeMode.LIGHT,ThemeMode.DARK,ThemeMode.SYSTEM).forEach {mode->
                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected=themeMode==mode,role=Role.RadioButton,onClick={
                        if(!ThemePreference.save(context,mode))status="Could not save theme. Try again."
                    }).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected=themeMode==mode,onClick=null)
                        Column {
                            Text(mode.label,style=MaterialTheme.typography.bodyLarge)
                            if(mode==ThemeMode.SYSTEM)Text("Follow your device theme",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }}
        item {HomeWidgetSettings()}
        item {SettingsSection("Meet Petot",if(companionOptions.first)"Reactions on" else "Reactions off",expandedSection=="petot",{toggleSection("petot")},icon="Petot") {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                PetotCharacter(PetotMood.HELLO,companionOptions.second)
                Column(Modifier.weight(1f)){Text("Your little money companion",style=MaterialTheme.typography.bodyMedium)}
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {PetotMood.entries.forEach {mood->FilterChip(previewMood==mood,{previewMood=mood},label={Text(mood.label)})}}
            OutlinedButton(onClick = { petotPreview = PetotReaction("Petot feels ${previewMood.label.lowercase()}", "${previewMood.caption}. This is only a preview; no records are changed.", previewMood, java.util.UUID.randomUUID().toString()) }, modifier = Modifier.fillMaxWidth()) { Text("Preview Petot") }
            Text("Gentle budget check-ins and little celebrations after saved entries. Runs on-device without AI API calls.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            PreferenceToggle("Companion reactions",companionOptions.first,{if(!CompanionPreference.save(context,"reactions",it))status="Could not save companion setting."})
            PreferenceToggle("Companion animations",companionOptions.second,{if(!CompanionPreference.save(context,"motion",it))status="Could not save animation setting."})
            PreferenceToggle("Companion sounds",companionSound,{if(!CompanionPreference.save(context,"sound",it))status="Could not save sound setting."})
            Text("Cheerful chimes for good news; gentle tones for budget warnings. Respects silent mode, notification volume and Do Not Disturb.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton({if(onStartTutorial!=null)onStartTutorial() else showTutorial=true},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                PilotIcon("Info",Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("View app tutorial")
            }
        }}
        item {SettingsSection("Reminders",if(reminders)"Reminders enabled" else "Reminders off",expandedSection=="reminders",{toggleSection("reminders")},icon="Reminders") {
            PreferenceToggle("Upcoming, due today & overdue",reminders,{enabled->if(enabled&&Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else {finance.setFlag("reminders",enabled);reminders=enabled;BackgroundWork.initialize(context);BackgroundWork.checkReminders(context)}})
            Text("Morning reminders around 9 AM, with background checks. Android may delay delivery. ‘Mark as paid’ opens the loan for confirmation; snooze postpones it one day.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({runCatching {context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}.onFailure {status="Open Android notification settings for Casha."}},contentPadding=PaddingValues(0.dp),shape=MaterialTheme.shapes.small){Text("Android notification settings")}}}
        item {SettingsSection("Receipt scanning","On-device photo and image reading",expandedSection=="receipts",{toggleSection("receipts")},icon="Receipt") {
            Text("Scan receipts from Add Expense, Log cash, or Petot, then review the details before saving.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        val nfcAutoExpense = finance.flag("nfc_auto_expense_enabled", false)
        val isNotificationAccessGranted = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        item {
            SettingsSection(
                title = "NFC & Payment Auto-Log",
                summary = if (nfcAutoExpense) (if (isNotificationAccessGranted) "Active · Listening for payment taps" else "Permission needed in Android Settings") else "Off · Auto-detect card & bank taps",
                expanded = expandedSection == "nfc_auto",
                onToggle = { toggleSection("nfc_auto") },
                icon = "Payday"
            ) {
                PreferenceToggle(
                    "Detect NFC, bank & payment SMS notifications",
                    nfcAutoExpense,
                    { enabled ->
                        finance.setFlag("nfc_auto_expense_enabled", enabled)
                        if (enabled && !isNotificationAccessGranted) {
                            runCatching {
                                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                            }.onFailure {
                                status = "Open Android Settings to grant Notification Access."
                            }
                        }
                    }
                )
                Text(
                    "Detects supported bank, wallet and completed-payment SMS notifications. Review the wallet and amount before saving; nothing is booked automatically. Notification Access and visible message previews are required. Old SMS inbox messages are not scanned.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (nfcAutoExpense) {
                    if (isNotificationAccessGranted) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PilotIcon("Check", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                                Text(
                                    "Notification Access granted. Ready to detect NFC taps.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                                }.onFailure {
                                    status = "Open Android Settings to grant Notification Access."
                                }
                            },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            PilotIcon("Settings", Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Grant Notification Access in Android Settings")
                        }
                    }
                }
            }
        }
        item {SettingsSection("Gemini assistant setup","${if(hasSavedKey)"API key saved (${if(isPaidTier)"Paid" else "Free"})" else "Not connected"} · ${assistantLanguage.label}",expandedSection=="gemini",{toggleSection("gemini")},icon="Gemini") {
            NoticeCard(
                if(isPaidTier)"Pay-as-you-go billing active" else "Free tier active",
                if(isPaidTier)"Requests are billed to your Google AI Studio / Google Cloud project. Daily limit and 30-second cooldown are unlocked."
                else "Protected with a 10 request/day limit and 30-second cooldown to guarantee zero unexpected charges."
            )
            if(hasSavedKey) {
                Surface(
                    color=MaterialTheme.colorScheme.secondaryContainer.copy(alpha=0.45f),
                    shape=RoundedCornerShape(10.dp),
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.secondary.copy(alpha=0.4f)),
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal=12.dp,vertical=10.dp),
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF15803D)))
                                Text("Key saved & active",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold,color=Color(0xFF15803D))
                            }
                            Text(maskedKey?:"••••••••••••••••",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            color=MaterialTheme.colorScheme.surface,
                            shape=RoundedCornerShape(50),
                            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                if(isPaidTier)"Paid" else "Free",
                                modifier=Modifier.padding(horizontal=8.dp,vertical=2.dp),
                                style=MaterialTheme.typography.labelSmall,
                                color=MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            TextButton({context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://aistudio.google.com/apikey")))},contentPadding=PaddingValues(0.dp),shape=MaterialTheme.shapes.small){Text("Open Google AI Studio")}
            OutlinedTextField(
                keyText,
                {keyText=it},
                label={Text(if(hasSavedKey)"Replace saved key (optional)" else "Gemini API key")},
                placeholder={Text("Paste your AI Studio API key here")},
                singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                visualTransformation=PasswordVisualTransformation(),
                modifier=Modifier.fillMaxWidth()
            )

            Text("API billing tier",style=MaterialTheme.typography.titleSmall)
            SegmentedToggle(
                options=listOf("Free tier","Pay-as-you-go (Paid)"),
                selectedIndex=if(isPaidTier) 1 else 0,
                onSelect={
                    val paid=(it==1)
                    isPaidTier=paid
                    finance.setFlag("ai_paid_tier",paid)
                },
                modifier=Modifier.fillMaxWidth()
            )

            if(!isPaidTier) {
                ConsentToggle("My API key's project has billing disabled",freeKey,{freeKey=it;finance.setFlag("freeKeyConfirmed",it)})
                Text("Free tier safety: 10 requests/day and one every 30 seconds in this app. Keeps usage completely free.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Surface(
                    color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.35f),
                    shape=RoundedCornerShape(8.dp),
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=0.3f))
                ) {
                    Row(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        PilotIcon("Gemini",Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
                        Text("Paid tier active: 10 requests/day cap and 30-second cooldown are unlocked. Requests are billed directly by Google according to your AI Studio account.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            ConsentToggle("Allow my typed command, anonymous loan amounts/dates and budget balances to be sent to Google when I tap Ask Gemini",consent,{consent=it;finance.setFlag("aiConsent",it)})
            Text("Google says free-tier content may be used to improve its products. Avoid typing sensitive details. Names from existing loan records are replaced with aliases; receipts, contacts, bank credentials and API keys are not included in the prompt.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick={
                        val clean=keyText.trim().removeSurrounding("\"").removeSurrounding("'").substringAfter("=").trim()
                        if(clean.length<10) {
                            status="Please paste a valid Gemini API key (at least 10 characters)."
                        } else {
                            coroutine.launch {
                                status=withContext(Dispatchers.IO){
                                    runCatching {
                                        check(keys.save(clean))
                                        if(!isPaidTier) {
                                            finance.setFlag("freeKeyConfirmed",true)
                                            freeKey=true
                                        }
                                        finance.setFlag("aiConsent",true)
                                        consent=true
                                        finance.notifyChanges()
                                        "Key saved and encrypted on this device."
                                    }.getOrElse {e->
                                        "Key could not be stored: ${e.message ?: "Check format and retry."}"
                                    }
                                }
                                keyText=""
                            }
                        }
                    },
                    enabled=keyText.isNotBlank(),
                    modifier=Modifier.weight(1f),
                    shape=MaterialTheme.shapes.medium
                ) {
                    PilotIcon("Check",Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save key")
                }
                OutlinedButton(
                    onClick={confirmDeleteKey=true},
                    enabled=hasSavedKey,
                    modifier=Modifier.weight(1f),
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
                    shape=MaterialTheme.shapes.medium
                ) {
                    PilotIcon("Trash",Modifier.size(16.dp),tint=MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Remove key")
                }
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Text("Assistant language",style=MaterialTheme.typography.titleSmall)
            Text("Choose whether Petot answers in Tagalog/Taglish or English.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                listOf(AssistantLanguage.ENGLISH,AssistantLanguage.TAGALOG).forEach {lang->
                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected=assistantLanguage==lang,role=Role.RadioButton,onClick={
                        if(finance.setAssistantLanguage(lang))assistantLanguage=lang else status="Could not save language setting."
                    }).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected=assistantLanguage==lang,onClick=null)
                        Column {
                            Text(lang.label,style=MaterialTheme.typography.bodyLarge)
                            Text(lang.description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }}
        val defaultSalaryAccount = moneyAccounts.find { it.id == defaultSalaryId }
        val salarySubtitle = if (defaultSalaryAccount != null) " · Default salary: ${defaultSalaryAccount.name}" else ""
        item {SettingsSection("Accounts & Wallets","${moneyAccounts.size} accounts · ${money(totalSavings)} in savings$salarySubtitle",expandedSection=="accounts",{toggleSection("accounts")},icon="Wallet") {
            Text("Manage spending accounts (Cash on hand, E-Wallet, Bank) and protected savings accounts.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                moneyAccounts.forEach {acc->
                    val bal=accountBalances[acc.id]?:0L
                    Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            PilotIcon(acc.icon,Modifier.size(18.dp),tint=if(acc.isSavings)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = acc.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (acc.id == defaultSalaryId) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = "Default Salary",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Balance: ${money(bal)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (bal < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Surface(
                                        color = if (acc.isSavings) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = if (acc.isSavings) "Savings · Safe" else "Spending",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (acc.isSavings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Button(onClick={manageAccounts=true},modifier=Modifier.fillMaxWidth().heightIn(min=FinanceDesign.TouchTarget),shape=MaterialTheme.shapes.medium){
                PilotIcon("Settings",Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Manage accounts & wallets")
            }
        }}
        item {SettingsSection("Privacy & backup",if(lock)"App lock on · export or restore" else "App lock off · export or restore",expandedSection=="privacy",{toggleSection("privacy")},icon="Security",modifier=Modifier.tutorialTarget("settings-security")) {
            PreferenceToggle("Require device unlock",lock,{enabled->
                val available=BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)==BiometricManager.BIOMETRIC_SUCCESS
                if(!enabled||available){if(context.getSharedPreferences("app_privacy",Context.MODE_PRIVATE).edit().putBoolean("appLock",enabled).commit())lock=enabled}
                else status="Set a device PIN, password or biometric before enabling app lock."
            })
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button({export.launch("Casha-${LocalDate.now()}.json")},modifier=Modifier.weight(1f),shape=MaterialTheme.shapes.medium){
                    PilotIcon("Share",Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export")
                }
                OutlinedButton({import.launch("application/json")},enabled=!restoring,modifier=Modifier.weight(1f),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shape=MaterialTheme.shapes.medium){
                    PilotIcon("Download",Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restore")
                }
            }
            Text("Restore adds new loan IDs without overwriting existing history. Budget settings/cash logs are restored only when the current device budget is empty; receipts must be copied separately.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Loans/payments sync only after you enable cloud sync. Receipts, API keys, budget logs and loan preferences stay on this device. Clearing app data removes unsynced and device-only data. No banking password is requested.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        status?.let {message->item {NoticeCard("Settings update",message)}}
        item {accountContent()}
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = remember(context) {
                        @Suppress("DEPRECATION")
                        val info = context.packageManager.getPackageInfo(context.packageName, 0)
                        "Casha v${info.versionName} (Build ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        // fallback tutorial overlay is rendered outside LazyColumn below
    }
    backup?.let {data->PilotModal(onDismissRequest={if(!restoring)backup=null},title={Text("Restore this backup?")},category="Backup & recovery",icon="Settings",accent=MaterialTheme.colorScheme.primary,text={Text("Add ${data.getJSONArray("loans").length()} backed-up loans to the current ledger. Matching IDs are skipped only if their records are identical; different histories stop the restore. Cloud-enabled accounts queue new records for upload. Financial metadata is restored only into an empty ledger. A backup from another ledger requires an empty destination and your confirmation here. Receipt images and API keys are excluded.")},confirmButton={Button(enabled=!restoring,shape=MaterialTheme.shapes.medium,onClick={restoring=true;coroutine.launch {
        val result=withContext(Dispatchers.IO){runCatching {
            val incoming=PortableBackup.validate(data,finance);val current=store.load();val old=current.associateBy {it.id}
            check(incoming.none {old.containsKey(it.id)&&old[it.id]!=it}) {"A loan ID has a different history. Nothing was overwritten."}
            val added=incoming.filter {it.id !in old}
            val metadata=finance.validatedBackupMetadata(data.getJSONObject("finance"))
            val financeEmpty=PortableBackup.financeIsEmpty(finance)
            check(data.getString("scope")==finance.scope || (financeEmpty && current.isEmpty())) { "Restore a different ledger into an empty destination only." }
            check(store !is CloudLoanRepository || (store.getQueue().isEmpty() && store.reconciliationStore.loadUnresolvedRecords().isEmpty())) { "Sync queued changes and resolve disputed payments before restoring." }
            check(finance.pendingPaymentIntents().isEmpty()) { "Resolve pending payments before restoring." }
            check(financeEmpty || added.isEmpty() || metadata.keys.none { it == "cash" || it == "profile" || it.startsWith("payment_account_") }) { "Use an empty destination to preserve this backup's wallet accounting. Existing budget data cannot be merged safely." }
            if(store is CloudLoanRepository) check(store.pauseBackupUploads()) { "Wait for account synchronization to finish before restoring." }
            val checkpointCreated = finance.createMultiStoreCheckpoint(LoanCodec.encode(current), store.journalSnapshot())
            if(!checkpointCreated && store is CloudLoanRepository) store.resumeBackupUploads()
            check(checkpointCreated) { "Could not create safety recovery checkpoint. Restore aborted." }
            try {
                if(added.isNotEmpty()) check(if(store is CloudLoanRepository) store.restoreBackup(added) else store.save(current+added,current)) { "Could not add new loan records." }
                val restored = if(financeEmpty) finance.restoreMetadata(metadata) else true
                check(restored) { "Budget metadata could not be saved." }
                if (checkpointCreated) {
                    check(finance.completeRestoreCheckpoint()) { "Restore saved but the recovery checkpoint could not be cleared." }
                }
                if(store is CloudLoanRepository) store.resumeBackupUploads()
                "Restored ${added.size} new loans. Existing records were retained. ${if(financeEmpty)"Budget metadata restored." else "Existing budget data kept."}"
            } catch (e: Exception) {
                if (checkpointCreated) {
                    finance.recoverFromRestoreCheckpointIfNeeded(store)
                    if (!finance.hasRestoreCheckpoint() && store is CloudLoanRepository) store.resumeBackupUploads()
                }
                throw e
            }
        }};result.onSuccess {status=it;backup=null}.onFailure {status=it.message?:"Restore failed. Existing records were not replaced."};restoring=false
    }}){Text(if(restoring)"Restoring…" else "Restore records")}},dismissButton={TextButton({backup=null},enabled=!restoring,shape=MaterialTheme.shapes.small){Text("Cancel")}})}
    if(showTutorial)InteractiveTutorialOverlay("Settings",{},{finance.setFlag("tutorial_seen",true);showTutorial=false})
    if(manageAccounts)ManageAccountsDialog(finance){manageAccounts=false}
    if(confirmDeleteKey) {
        PilotModal(
            onDismissRequest={confirmDeleteKey=false},
            title={Text("Remove Gemini API key?")},
            category="API security",
            icon="Delete",
            accent=MaterialTheme.colorScheme.error,
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Are you sure you want to remove your API key?")
                    Text("Your encrypted API key will be permanently deleted from this device. Petot AI assistant features will be paused until you enter a key again.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton={
                Button(
                    onClick={
                        confirmDeleteKey=false
                        coroutine.launch {
                            status=withContext(Dispatchers.IO){
                                if(keys.remove()){
                                    finance.setFlag("has_key", false)
                                    finance.notifyChanges()
                                    "API key removed from device."
                                } else "Could not remove key."
                            }
                        }
                    },
                    colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),
                    shape=MaterialTheme.shapes.medium
                ) {
                    Text("Confirm remove")
                }
            },
            dismissButton={
                TextButton(onClick={confirmDeleteKey=false},shape=MaterialTheme.shapes.small){Text("Cancel")}
            }
        )
    }
}
}

@Composable
fun LoanPreferences(loan:Loan,finance:FinanceStore) {
    val revision by finance.changes.collectAsState()
    var editing by remember {mutableStateOf(false)};var showStatement by remember {mutableStateOf(false)};var status by remember {mutableStateOf<String?>(null)}
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->if(result.resultCode==Activity.RESULT_OK) {
        val phone=runCatching {result.data?.data?.let {uri->context.contentResolver.query(uri,null,null,null,null)?.use {cursor->
            if(cursor.moveToFirst())cursor.getString(cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)) else null
        }}}.getOrNull()
        status=if(phone!=null&&finance.setContact(loan.id,phone))"Contact linked on this device." else "Could not read the selected phone number."
    }}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            FilledTonalButton({editing=true},shape=MaterialTheme.shapes.medium){
                PilotIcon("Settings",Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Loan preferences")
            }
            OutlinedButton({showStatement=true},shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                PilotIcon("Receipt",Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Statement of account")
            }
            if(loan.remaining==0L)Text("Paid in full · Automatically archived",style=MaterialTheme.typography.bodySmall)
            else if(finance.flag("archived_${loan.id}"))OutlinedButton({finance.archive(loan.id,false);status="Restored to dashboard."},shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Text("Restore to dashboard")}
        }
        if(loan.mode==LoanMode.OWED_TO_ME) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                OutlinedButton({runCatching {picker.launch(Intent(Intent.ACTION_PICK,android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI))}.onFailure {status="No contacts picker is available."}},shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Text("Link a contact")}
                OutlinedButton({
                    val next=loan.installments().firstOrNull {it.remaining>0}
                    val message="Hi ${loan.party}! Friendly reminder about ${money(next?.remaining?:0)} due ${next?.due?:"soon"}. Please let me know when you can send it. Thank you!"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,message),"Share reminder draft"))
                },enabled=loan.remaining>0,shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                    PilotIcon("Share",Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share polite reminder")
                }
                if(finance.contact(loan.id).isNotBlank())OutlinedButton({runCatching {context.startActivity(Intent(Intent.ACTION_SENDTO,Uri.parse("smsto:${Uri.encode(finance.contact(loan.id))}")).putExtra("sms_body","Hi ${loan.party}! Friendly reminder about your outstanding ${money(loan.remaining)}. Thank you!"))}.onFailure {status="No SMS app is available."}},shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                    PilotIcon("Send",Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open SMS reminder draft")
                }
            }
        }
        status?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)}
        loan.payments.forEach {payment->finance.proof(payment.id)?.let {proof->
            Text("Payment reference: ${proof.optString("reference").ifBlank {"not supplied"}}",style=MaterialTheme.typography.bodySmall)
            val receipt=proof.optString("receipt")
            if(receipt.isNotBlank())TextButton({runCatching {
                val file=ReceiptScanner.file(context,receipt,finance.scope);val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure {status="Receipt is unavailable on this device."}},contentPadding=PaddingValues(0.dp),shape=MaterialTheme.shapes.small){
                PilotIcon("Receipt",Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("View payment receipt")
            }
        }}
    }
    if(showStatement) {
        val statementText=remember(loan) {PaydayPlanner.formatStatement(loan,LocalDate.now())}
        var copied by remember {mutableStateOf(false)}
        PilotModal(
            onDismissRequest={showStatement=false},
            title={Text("Statement of account")},
            category=loan.party,
            icon="Loans",
            accent=MaterialTheme.colorScheme.primary,
            text={
                Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.large,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(statementText,Modifier.padding(14.dp),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    if(copied) Text("Copied to clipboard!",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary)
                }
            },
            confirmButton={
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Statement of Account",statementText))
                        copied=true
                    },shape=MaterialTheme.shapes.medium){
                        PilotIcon("Receipt",Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                    Button({
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,statementText),"Share statement"))
                    },shape=MaterialTheme.shapes.medium){
                        PilotIcon("Share",Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                }
            },
            dismissButton={TextButton({showStatement=false},shape=MaterialTheme.shapes.small){Text("Close")}}
        )
    }
    if(editing) {
        val original=finance.policy(loan.id)
        var apr by rememberSaveable {mutableStateOf(java.math.BigDecimal.valueOf(original.annualRateBps.toLong(),2).toPlainString())}
        var risk by rememberSaveable {mutableStateOf(original.risk)};var split by rememberSaveable {mutableStateOf(original.allowSplit)}
        var archived by rememberSaveable {mutableStateOf(finance.flag("archived_${loan.id}"))};var error by remember {mutableStateOf<String?>(null)}
        PilotModal(onDismissRequest={editing=false},title={Text("Loan preferences")},category=loan.party,icon="Settings",accent=MaterialTheme.colorScheme.primary,text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            EntryField(apr,{apr=it},"Annual interest % (strategy only)",true)
            Text("This ranks extra payments; it does not change the agreed loan total.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Missed-payment risk",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    listOf("Flexible","Normal","High penalty").forEachIndexed {i,label->FilterChip(risk==i,{risk=i},label={Text(label)})}
                }
            }
            ConsentToggle("Allow 50% next-cutoff sinking reserve",split,{split=it})
            if(loan.remaining==0L)Text("Fully paid loans are archived automatically. Reversing a payment restores the loan if a balance remains.",style=MaterialTheme.typography.bodySmall)
            else ConsentToggle("Archive: hide from dashboard and stop reminders; keep all records",archived,{archived=it})
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }},confirmButton={Button({val rate=nonnegative(apr)
            if(rate==null||rate>100_000)error="Use an annual rate from 0 to 1000%, with at most 2 decimals."
            else if(finance.savePolicy(loan.id,DebtPolicy(rate.toInt(),risk,split))&&finance.archive(loan.id,archived)){editing=false;status="Preferences saved on this device."}
            else error="Could not save preferences."
        },shape=MaterialTheme.shapes.medium){Text("Save preferences")}},dismissButton={TextButton({editing=false},shape=MaterialTheme.shapes.small){Text("Cancel")}})
    }
}
