package com.example.casha.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.casha.data.FinanceStore
import com.example.casha.data.LoanRepository
import com.example.casha.data.CapturedPayment
import com.example.casha.domain.*
import com.example.casha.reminders.BackgroundWork
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun money(amount: Long): String = NumberFormat.getCurrencyInstance().apply {
    currency = Currency.getInstance("PHP")
}.format(java.math.BigDecimal.valueOf(amount, 2))

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CashaApp(store: LoanRepository, featureScope:String?=null, reminderRequest:Pair<String,String>?=null,activateReminders:Boolean=false,capturedRequest:Pair<String,String>?=null,widgetRequest:Pair<String,String>?=null,onWidgetHandled:()->Unit={},accountContent: @Composable () -> Unit = {}) {
    val context=LocalContext.current
    val isolatedScope=remember {featureScope?:"test_${UUID.randomUUID()}"}
    val finance=remember(isolatedScope) {
        FinanceStore(context,isolatedScope).apply {
            recoverFromRestoreCheckpointIfNeeded(store)
            if (!hasRestoreCheckpoint() && store is com.example.casha.data.CloudLoanRepository) store.resumeBackupUploads()
        }
    }
    val featureRevision by finance.changes.collectAsState()
    var widgetCashError by remember {mutableStateOf<String?>(null)}
    var widgetCash by rememberSaveable {mutableStateOf(false)}
    var quickExpense by rememberSaveable {mutableStateOf(false)}
    val snackbar=remember {SnackbarHostState()}
    var page by rememberSaveable {mutableStateOf("Loans")}
    var query by rememberSaveable {mutableStateOf("")}
    var largestFirst by rememberSaveable {mutableStateOf(false)}
    var showArchived by rememberSaveable {mutableStateOf(false)}
    var loans by remember { mutableStateOf<List<Loan>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(LoanMode.I_OWE) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var simulator by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var reminderReview by rememberSaveable {mutableStateOf(false)}
    var showTutorial by rememberSaveable(isolatedScope) {mutableStateOf(activateReminders&&!finance.flag("tutorial_seen"))}
    val tutorialSpotlights = remember { TutorialSpotlights() }
    val scope = rememberCoroutineScope()
    val companionOptions by rememberCompanionOptions()
    var reaction by remember {mutableStateOf<PetotReaction?>(null)}
    var observedLoans by remember {mutableStateOf<List<Loan>?>(null)}
    var observedCash by remember {mutableStateOf<List<CashEntry>?>(null)}
    LaunchedEffect(loans,featureRevision,ready,companionOptions.first) {
        if(ready) {
            val cash=runCatching {finance.cashEntries()}.getOrNull()
            if(cash!=null) {
                val previousLoans=observedLoans;val previousCash=observedCash
                previousLoans?.filter {old->loans.none {it.id==old.id}}?.forEach {removed->context.getSystemService(android.app.NotificationManager::class.java).cancel(removed.id.hashCode())}
                if(companionOptions.first&&previousLoans!=null&&previousCash!=null) {
                    runCatching {companionReaction(previousLoans,loans,previousCash,cash,finance.profile(),loans.associate {it.id to finance.policy(it.id)},finance.preferences.contains("profile"))}.getOrNull()?.let {reaction=it.copy(eventId=UUID.randomUUID().toString())}
                    if(cash.any {it.id.startsWith("undo_")&&previousCash.none {old->old.id==it.id}})reaction=PetotReaction("All sorted!","Entry reversed. Your original record stays in history.",PetotMood.HELLO,UUID.randomUUID().toString())
                }
                observedLoans=loans;observedCash=cash
            }
        }
        if(!companionOptions.first)reaction=null
    }
    val visibleLoans=remember(loans,mode,showArchived,featureRevision) {loans.filter {it.mode==mode&&(showArchived||(it.remaining>0&&!finance.flag("archived_${it.id}")))}}
    LaunchedEffect(loans,ready) {
        if(ready&&featureScope!=null&&activateReminders)withContext(Dispatchers.IO) {
            finance.cacheLoans(loans)
            BackgroundWork.checkReminders(context)
        }
    }
    LaunchedEffect(widgetRequest,ready) {
        if(widgetRequest!=null && ready) {
            if(widgetRequest.first==isolatedScope) {
                selectedId=null
                when(widgetRequest.second) {
                    "cash"->{page="Payday";quickExpense=false;widgetCashError=null;widgetCash=true}
                    "expense"->{widgetCash=false;quickExpense=true}
                    "loans"->{page="Loans";mode=LoanMode.I_OWE}
                    "payday"->{page="Payday"}
                }
            }
            onWidgetHandled()
        }
    }
    LaunchedEffect(reminderRequest,ready) {
        if(ready&&reminderRequest!=null&&reminderRequest.first==featureScope) {
            selectedId=reminderRequest.second;reminderReview=true;page="Loans"
        }
    }
    val capturedList = remember(featureRevision) { finance.capturedPayments() }
    var reviewingCaptured by remember { mutableStateOf<CapturedPayment?>(null) }
    LaunchedEffect(capturedList, capturedRequest) {
        if (capturedRequest != null && capturedRequest.first == isolatedScope) {
            val target = capturedList.find { it.id == capturedRequest.second }
            if (target != null) {
                reviewingCaptured = target
                return@LaunchedEffect
            }
        }
        if (reviewingCaptured == null && capturedList.isNotEmpty()) {
            reviewingCaptured = capturedList.first()
        } else if (reviewingCaptured != null && capturedList.none { it.id == reviewingCaptured?.id }) {
            reviewingCaptured = capturedList.firstOrNull()
        }
    }
    LaunchedEffect(store) {
        store.changes.collect {
            val loaded = withContext(Dispatchers.IO) { runCatching { store.load() } }
            loaded.onSuccess {
                loans = it
                ready = true
                withContext(Dispatchers.IO) { runCatching { finance.reconcileStartupIntents(it) } }
            }.onFailure {
                ready = false
                failure = "Saved records could not be read. Changes are disabled to protect your data."
            }
        }
    }
    fun persist(updated: List<Loan>, done: () -> Unit = {}) {
        if (!ready || busy) return
        busy = true
        val expected = loans
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { store.save(updated, expected) }.getOrDefault(false) }
            if (saved) { loans = updated; failure = null; done() } else failure = "Could not save. Records may have changed; review the latest values and retry."
            busy = false
        }
    }
    fun deleteLoan(loan:Loan) {
        if(!ready||busy)return
        busy=true
        scope.launch {
            val deleted=withContext(Dispatchers.IO){runCatching {store.deleteUnpaid(loan)}.getOrDefault(false)}
            if(deleted) {
                loans=withContext(Dispatchers.IO){runCatching {store.load()}.getOrDefault(loans.filterNot {it.id==loan.id})}
                selectedId=null;reminderReview=false;failure=null
                // Remove this loan from the device reminder cache as soon as deletion is committed.
                withContext(Dispatchers.IO){runCatching {finance.cacheLoans(loans)}}
            } else failure="Could not delete. The loan may have changed or contain payments. Review the latest record."
            busy=false
        }
    }
    BackHandler(enabled=page!="Loans"&&selectedId==null) {page="Loans"}
    CompositionLocalProvider(LocalTutorialSpotlights provides tutorialSpotlights) {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!WindowInsets.isImeVisible) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf("Loans", "Payday", "Expense", "AI", "Settings").forEach { title ->
                        NavigationBarItem(
                            selected = page == title || (page == "Assist" && title == "AI"),
                            onClick = { if (title == "Expense") quickExpense = true else page = title },
                            icon = {
                                if (title == "Expense") {
                                    Box(Modifier.tutorialTarget("nav-Expense").semantics { contentDescription = "Add expense" }) {
                                        PilotIcon("Plus")
                                    }
                                } else {
                                    PilotIcon(title)
                                }
                            },
                            label = { Text(title, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
        }
    ) { padding ->
        val contentModifier = Modifier.padding(padding).consumeWindowInsets(padding)
        when(page) {
            "Payday" -> PaydayScreen(loans,finance,contentModifier,onAssistant={page="AI"}) {id->selectedId=id;page="Loans"}
            "AI", "Assist" -> AutomationScreen(store,loans,finance,contentModifier)
            "Settings" -> SettingsScreen(store,finance,contentModifier,onStartTutorial={page="Loans";showTutorial=true},accountContent=accountContent)
            "Calendar" -> PaymentCalendar(loans,contentModifier,finance) {id->selectedId=id;page="Loans"}
            else ->
        LazyColumn(contentModifier.fillMaxSize().testTag("loan-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        ScreenHeading("Loans & receivables", "Balances and upcoming payments", "CASHA")
                    }
                    OutlinedIconButton(
                        onClick = { page = "Calendar" },
                        modifier = Modifier.testTag("open-calendar").tutorialTarget("open-calendar").semantics { contentDescription = "Open payment calendar" },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        PilotIcon("Calendar")
                    }
                }
            }
            item {
                SegmentedToggle(
                    options = listOf("I Owe", "Owed to Me"),
                    selectedIndex = if (mode == LoanMode.I_OWE) 0 else 1,
                    onSelect = { mode = if (it == 0) LoanMode.I_OWE else LoanMode.OWED_TO_ME },
                    modifier = Modifier.tutorialTarget("ledger-mode")
                )
            }
            item {
                val total=visibleLoans.sumOf {it.total}
                val remaining=visibleLoans.sumOf {it.remaining}
                val paid=total-remaining
                val lastDue=visibleLoans.flatMap {it.installments()}.filter {it.remaining>0}.maxOfOrNull {it.due}
                HeroCard {
                    Text(if(mode==LoanMode.I_OWE)"Remaining to pay" else "Remaining to collect",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(money(remaining),style=MaterialTheme.typography.headlineLarge)
                    Text("${visibleLoans.count {it.remaining>0}} active loans",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(total>0) {
                        LinearProgressIndicator(progress={paid.toFloat()/total.toFloat()},modifier=Modifier.fillMaxWidth().height(4.dp),color=MaterialTheme.colorScheme.secondary,trackColor=MaterialTheme.colorScheme.surfaceContainerHighest)
                        Text("${money(paid)} recorded as paid",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(mode==LoanMode.I_OWE&&lastDue!=null) {
                        FilledTonalButton(
                            onClick={simulator=true},
                            shape=RoundedCornerShape(50),
                            contentPadding=PaddingValues(horizontal=12.dp,vertical=6.dp)
                        ) {
                            PilotIcon("Calculator",Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Payoff simulator",style=MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { adding = true },
                        enabled = ready && !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp).tutorialTarget("add-loan"),
                        shape = MaterialTheme.shapes.large
                    ) {
                        PilotIcon("Plus", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add loan")
                    }

                }
            }
            item {
                val outstanding=visibleLoans.flatMap {loan->loan.installments().filter {it.remaining>0}.map {loan to it}}.sortedBy {it.second.due}
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Coming up","Next payment per loan · tap to open")
                    if(outstanding.isEmpty()) {
                        Text("No upcoming payments. You're all caught up.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val upcomingItems = outstanding.distinctBy {it.first.id}.take(3)
                        Surface(
                            modifier=Modifier.fillMaxWidth().testTag("upcoming-container"),
                            shape=MaterialTheme.shapes.large,
                            color=MaterialTheme.colorScheme.surface,
                            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                upcomingItems.forEachIndexed {index,(loan,inst)->
                                    UpcomingLoanRow(loan,inst,inContainer=true){selectedId=loan.id}
                                    if(index < upcomingItems.lastIndex) {
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
            if (failure != null) item { Text(failure!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Your loans")
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(200) },
                        label = { Text("Search loans") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().testTag("loan-search"),
                        leadingIcon = { PilotIcon("Search", Modifier.size(18.dp)) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.semantics { contentDescription = "Clear search" }) { PilotIcon("Close", Modifier.size(16.dp)) } }
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(!largestFirst, { largestFirst = false }, label = { Text("Due soon", maxLines = 1) })
                        FilterChip(largestFirst, { largestFirst = true }, label = { Text("Largest balance", maxLines = 1) })
                        FilterChip(showArchived, { showArchived = !showArchived }, label = { Text("Show archived", maxLines = 1) })
                    }
                }
            }
            val visible = visibleLoans.filter {it.party.contains(query.trim(),ignoreCase=true)}.let {list->if(largestFirst)list.sortedByDescending {it.remaining} else list.sortedBy {it.installments().firstOrNull {i->i.remaining>0}?.due?:LocalDate.MAX}}
            if (!ready && failure==null) item {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text("Loading records…",style=MaterialTheme.typography.bodyMedium)}}
            if (ready && visible.isEmpty()) item {
                if (query.isNotBlank()) {
                    NoticeCard("No matching loans", "Try a different name or clear your search.", "Clear search", { query = "" })
                } else if (loans.isEmpty()) {
                    NoticeCard("No loans recorded", "Track money you owe to lenders or money borrowers owe you. Tap 'Add loan' to create your first schedule.", "Add your first loan", { adding = true })
                } else {
                    NoticeCard("No active loans", "Add a loan or enable Show archived to view fully paid loans.")
                }
            }
            if (ready && visible.isNotEmpty()) item {
                Surface(
                    modifier=Modifier.fillMaxWidth().testTag("loans-container"),
                    shape=MaterialTheme.shapes.large,
                    color=MaterialTheme.colorScheme.surface,
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        visible.forEachIndexed {index,loan->
                            val next=loan.installments().firstOrNull {it.remaining>0}
                            val urgency=dueUrgencyColors(next?.due,loan.remaining)
                            val isPaid=next==null||loan.remaining<=0L

                            Row(
                                modifier=Modifier
                                    .fillMaxWidth()
                                    .clickable {selectedId=loan.id}
                                    .testTag("loan-item-${loan.id}")
                                    .padding(horizontal=16.dp,vertical=14.dp),
                                horizontalArrangement=Arrangement.SpaceBetween,
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier=Modifier.weight(1f).padding(end=12.dp),
                                    verticalArrangement=Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text=loan.party,
                                        style=MaterialTheme.typography.titleMedium,
                                        fontWeight=FontWeight.SemiBold,
                                        maxLines=1,
                                        overflow=TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment=Alignment.CenterVertically,
                                        horizontalArrangement=Arrangement.spacedBy(6.dp)
                                    ) {
                                        if(isPaid) {
                                            Surface(
                                                color=MaterialTheme.colorScheme.secondary.copy(alpha=0.15f),
                                                shape=RoundedCornerShape(50)
                                            ) {
                                                Text(
                                                    "Paid in full",
                                                    modifier=Modifier.padding(horizontal=8.dp,vertical=2.dp),
                                                    style=MaterialTheme.typography.labelSmall,
                                                    color=MaterialTheme.colorScheme.secondary,
                                                    fontWeight=FontWeight.Medium
                                                )
                                            }
                                        } else {
                                            Surface(
                                                color=urgency.accent.copy(alpha=0.12f),
                                                shape=RoundedCornerShape(50)
                                            ) {
                                                Text(
                                                    urgency.label,
                                                    modifier=Modifier.padding(horizontal=8.dp,vertical=2.dp),
                                                    style=MaterialTheme.typography.labelSmall,
                                                    color=urgency.accent,
                                                    fontWeight=FontWeight.SemiBold
                                                )
                                            }
                                            Text(
                                                "${next!!.due.format(DateTimeFormatter.ofPattern("MMM d"))} · ${money(next.remaining)}",
                                                style=MaterialTheme.typography.bodySmall,
                                                color=if(next.due.isBefore(LocalDate.now())) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment=Alignment.CenterVertically,
                                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(horizontalAlignment=Alignment.End) {
                                        Text(
                                            text=money(loan.remaining),
                                            style=MaterialTheme.typography.titleMedium,
                                            fontWeight=FontWeight.Bold,
                                            color=if(loan.remaining>0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary
                                        )
                                        Text(
                                            text=if(loan.mode==LoanMode.I_OWE)"remaining" else "to collect",
                                            style=MaterialTheme.typography.labelSmall,
                                            color=MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    PilotIcon(
                                        "ChevronRight",
                                        Modifier.size(16.dp),
                                        tint=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f)
                                    )
                                }
                            }

                            if(index < visible.lastIndex) {
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
    if(widgetCash)CashDialog({widgetCash=false},finance,saveError=widgetCashError) {entry->
        if(runCatching {finance.addCash(entry)}.getOrDefault(false))widgetCash=false else widgetCashError="Could not save. Your entry is still here; try again."
    }
    if(quickExpense)QuickExpenseDialog(finance,{quickExpense=false}) {entry->
        quickExpense=false
        if(entry.id.startsWith("receipt_"))page="Payday"
        scope.launch {
            val result=snackbar.showSnackbar("Expense saved · ${money(entry.amount)} · ${entry.channel} · ${entry.date}",actionLabel="Undo",duration=SnackbarDuration.Long)
            if(result==SnackbarResult.ActionPerformed) {
                val undone=withContext(Dispatchers.IO){runCatching {finance.reverseCash(entry.id)}.getOrDefault(false)}
                snackbar.showSnackbar(if(undone)"Expense reversed" else "Could not undo. Open Payday activity to review.")
            }
        }
    }
    if (adding) AddLoanDialog(mode, busy, { if (!busy) adding = false }, saveError = failure) { loan ->
        persist(loans + loan) { adding = false }
    }
    if (simulator) {
        val profile = remember(featureRevision) { runCatching { finance.profile() }.getOrNull() }
        if (profile != null) DebtFreeSimulatorDialog(loans, finance, profile, { simulator = false })
    }
    loans.find { it.id == selectedId }?.let { loan ->
        if (editing) AddLoanDialog(loan.mode, busy, { if (!busy) editing = false }, loan, failure) { updated ->
            if(loans.find {it.id==loan.id}!=loan)failure="This loan changed. Review the latest record before saving."
            else persist(loans.map { if (it.id == updated.id) updated else it }) { editing = false }
        } else LoanDetails(loan, finance,busy, { if (!busy) {selectedId = null;reminderReview=false} }, { editing = true }, failure,reminderReview,delete={deleteLoan(loan)}) { updated ->
            if(loans.find {it.id==loan.id}!=loan)failure="This loan changed. Review the latest record before saving."
            else persist(loans.map { if (it.id == updated.id) updated else it }) {
                scope.launch(Dispatchers.IO) { runCatching { finance.reconcileStartupIntents(listOf(updated)) } }
            }
        }
    }
    reaction?.let {shown->PetotPopup(shown,companionOptions.second){if(reaction?.eventId==shown.eventId)reaction=null}}
    var captureSaveError by remember(reviewingCaptured?.id) { mutableStateOf<String?>(null) }
    reviewingCaptured?.let { payment ->
        CapturedPaymentConfirmDialog(
            payment = payment,
            finance = finance,
            totalPending = capturedList.size,
            saveError = captureSaveError,
            loans = loans,
            onConfirm = { note, channel, amount ->
                captureSaveError = null
                val ok = runCatching { finance.confirmCapturedPayment(payment.id, note, channel, amount) }.getOrDefault(false)
                if (!ok) captureSaveError = "Could not save. Check that the source and cash wallets are active and different, then try again. Your capture is still here."
                if (ok) {
                    reviewingCaptured = null
                    scope.launch {
                        val amountFormatted = "₱" + java.text.DecimalFormat("#,##0.00").format(amount / 100.0)
                        snackbar.showSnackbar("Logged $amountFormatted to $channel")
                    }
                }
            },
            onApplyToLoan = { matchedLoan, instIndex, channel, amount ->
                finance.dismissCapturedPayment(payment.id)
                reviewingCaptured = null
                val paymentOpId = java.util.UUID.randomUUID().toString()
                val targetAcc = finance.moneyAccounts(includeArchived = true).find { it.name.equals(channel, ignoreCase = true) }
                val intent = com.example.casha.data.PendingPaymentIntent(
                    intentId = paymentOpId,
                    paymentId = paymentOpId,
                    loanId = matchedLoan.id,
                    installmentIndex = instIndex,
                    accountId = targetAcc?.id ?: "acc_cash",
                    accountName = targetAcc?.name ?: channel,
                    amountCentavos = amount,
                    date = payment.date,
                    isReceivable = (matchedLoan.mode == com.example.casha.domain.LoanMode.OWED_TO_ME)
                )
                finance.savePaymentIntent(intent)
                val updatedLoan = matchedLoan.pay(instIndex, amount, paymentOpId, payment.date)
                persist(loans.map { if (it.id == updatedLoan.id) updatedLoan else it }) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { finance.reconcileStartupIntents(listOf(updatedLoan)) }
                    }
                    scope.launch {
                        val amountFormatted = "₱" + java.text.DecimalFormat("#,##0.00").format(amount / 100.0)
                        snackbar.showSnackbar("Applied $amountFormatted to ${matchedLoan.party}")
                    }
                }
            },
            onDismiss = {
                finance.dismissCapturedPayment(payment.id)
                reviewingCaptured = null
            }
        )
    }
    if(showTutorial)InteractiveTutorialOverlay(page,{page=it},{finance.setFlag("tutorial_seen",true);showTutorial=false})
    }
    }

}

@Composable
internal fun AddLoanDialog(mode: LoanMode, busy: Boolean, dismiss: () -> Unit, existing: Loan? = null,
                          saveError: String? = null,draft:Boolean=false, save: (Loan) -> Unit) {
    var party by rememberSaveable { mutableStateOf(existing?.party ?: "") }
    var total by rememberSaveable { mutableStateOf(existing?.let { java.math.BigDecimal.valueOf(it.total, 2).toPlainString() } ?: "") }
    var count by rememberSaveable { mutableStateOf(existing?.count?.toString() ?: "1") }
    var date by rememberSaveable { mutableStateOf((existing?.firstDue ?: LocalDate.now().plusMonths(1)).toString()) }
    var frequency by rememberSaveable { mutableStateOf(existing?.frequency ?: Frequency.MONTHLY) }
    val financialFieldsEnabled = existing?.payments.isNullOrEmpty() && !busy
    var error by remember { mutableStateOf<String?>(null) }
    var estimate by remember {mutableStateOf(false)}
    val parsedTotal = parseMoney(total)
    val parsedCount = count.toIntOrNull()
    val perInstallment = if (parsedTotal != null && parsedCount != null && parsedCount in 1..120 && parsedTotal >= parsedCount) parsedTotal / parsedCount else null
    val modalAccent = if (mode == LoanMode.I_OWE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    PilotModal(
        onDismissRequest = dismiss,
        category = if (draft) "AI PROPOSAL" else if (existing != null) "EDIT RECORD" else if (mode == LoanMode.I_OWE) "NEW DEBT" else "NEW RECEIVABLE",
        icon = "Loans",
        accent = modalAccent,
        title = { Text(if(draft)"Review new loan" else if (existing != null) "Edit loan" else "Add ${if (mode == LoanMode.I_OWE) "debt" else "collectible"}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp).testTag("loan-form"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Enter the agreed total, including interest and fees. The estimator is optional; your lender's agreement controls.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if(financialFieldsEnabled)item {OutlinedButton({estimate=true}, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, modalAccent.copy(alpha = 0.4f))){
                    PilotIcon("Calculator", Modifier.size(16.dp), tint = modalAccent)
                    Spacer(Modifier.width(6.dp))
                    Text("Estimate total with interest", color = modalAccent)
                }}
                if (existing != null && existing.payments.isNotEmpty()) item {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Text("You can change the name. Amounts and dates stay locked after a payment to preserve its history, including reversed payments.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { OutlinedTextField(party, { party = it.take(200) }, label = { Text("Person or lender") },modifier=Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, singleLine = true) }
                item { OutlinedTextField(total, { total = it }, enabled = financialFieldsEnabled, label = { Text("Total payable (PHP)") },modifier=Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true) }
                item { OutlinedTextField(count, { count = it }, enabled = financialFieldsEnabled, label = { Text("Installments (1–120)") },modifier=Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) }
                if (perInstallment != null) {
                    item {
                        Surface(
                            color = modalAccent.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, modalAccent.copy(alpha = 0.3f)),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Estimated per installment", style = MaterialTheme.typography.labelMedium, color = modalAccent)
                                Text("~${money(perInstallment)} × $parsedCount", style = MaterialTheme.typography.titleSmall, color = modalAccent)
                            }
                        }
                    }
                }
                item { DateEntryField(date,{date=it},"First due: YYYY-MM-DD",financialFieldsEnabled) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Repayment schedule", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Frequency.entries.forEach { value ->
                                FilterChip(frequency == value, { frequency = value }, enabled = financialFieldsEnabled, label = { Text(when (value) {
                                    Frequency.MONTHLY -> "Monthly"
                                    Frequency.WEEKLY -> "Weekly"
                                    Frequency.FORTNIGHTLY -> "Every 14 days"
                                    Frequency.SEMIMONTHLY -> "15th & month-end"
                                }) })
                            }
                        }
                    }
                }
                if (frequency == Frequency.SEMIMONTHLY) item { Text("Choose the 15th or the last day of the month as the first due date. February uses its actual last day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (saveError != null) item { Text(saveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(enabled = !busy, shape = MaterialTheme.shapes.medium, onClick = {
            val amount = parseMoney(total)
            val n = count.toIntOrNull()
            val due = runCatching { LocalDate.parse(date) }.getOrNull()
            if (party.isBlank() || amount == null || n == null || n !in 1..120 || amount < n || due == null || due.year !in 1900..2200) {
                error = "Enter a name, valid amount (up to 2 decimals), 1–120 installments, and a valid date (1900–2200)."
            } else {
                val updated = runCatching {
                    existing?.edit(party, amount, n, due, frequency)
                        ?: Loan(party = party.trim(), mode = mode, total = amount, count = n, firstDue = due, frequency = frequency)
                }.getOrNull()
                if (updated == null) error = "For a twice-monthly schedule, choose the 15th or the last day of the month. Recorded payment schedules cannot be changed."
                else save(updated)
            }
        }) {
            PilotIcon("Check", Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (busy) "Saving…" else "Save loan")
        } },
        dismissButton = { TextButton(dismiss, enabled = !busy,shape=MaterialTheme.shapes.small) { Text("Cancel") } })
    if(estimate)InterestDialog(count.toIntOrNull()?:1,frequency,{estimate=false}) {value->total=java.math.BigDecimal.valueOf(value,2).toPlainString();estimate=false}
}

@Composable private fun InterestDialog(count:Int,frequency:Frequency,dismiss:()->Unit,use:(Long)->Unit) {
    var principal by rememberSaveable {mutableStateOf("")};var rate by rememberSaveable {mutableStateOf("")}
    var reducing by rememberSaveable {mutableStateOf(true)};var error by remember {mutableStateOf<String?>(null)}
    val preview=remember(principal,rate,count,frequency,reducing) {
        runCatching {
            val p=requireNotNull(parseMoney(principal));val r=if(rate.trim() in listOf("0","0.0","0.00"))0L else requireNotNull(parseMoney(rate))
            require(r<=100_000);InterestEstimate.total(p,r.toInt(),count.coerceIn(1,120),frequency,reducing)
        }.getOrNull()
    }
    PilotModal(
        onDismissRequest=dismiss,
        category="INTEREST CALCULATOR",
        icon="Payday",
        accent=MaterialTheme.colorScheme.secondary,
        title={Text("Interest estimate")},
        text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Uses $count ${frequency.name.lowercase()} periods. No fees or lender-specific day-count rules are assumed.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(principal,{principal=it;error=null},label={Text("Principal (PHP)")},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            OutlinedTextField(rate,{rate=it;error=null},label={Text("Annual interest %")},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            ConsentToggle("Reducing-balance estimate (off = flat)",reducing,{reducing=it})
            preview?.let {est->
                Surface(color=MaterialTheme.colorScheme.secondaryContainer,border=BorderStroke(1.dp,MaterialTheme.colorScheme.secondary.copy(alpha=0.3f)),shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Text("Estimated total",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(money(est),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }},
        confirmButton={Button(shape=MaterialTheme.shapes.medium,onClick={val value=runCatching {
            val p=requireNotNull(parseMoney(principal));val r=if(rate in listOf("0","0.0","0.00"))0L else requireNotNull(parseMoney(rate))
            require(r<=100_000);InterestEstimate.total(p,r.toInt(),count,frequency,reducing)
        }.getOrNull();if(value==null)error="Check the principal, annual rate (0–1000%) and installment count." else use(value)}){
            PilotIcon("Check", Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Use estimated total")
        }},
        dismissButton={TextButton(dismiss,shape=MaterialTheme.shapes.small){Text("Cancel")}}
    )
}

@Composable
private fun LoanDetails(loan: Loan,finance:FinanceStore, busy: Boolean, dismiss: () -> Unit, edit: () -> Unit,
                        saveError: String?, prefillFull:Boolean=false,delete:()->Unit,save: (Loan) -> Unit) {
    var showPayment by rememberSaveable(loan.id) {mutableStateOf(prefillFull)}
    var confirmDelete by rememberSaveable(loan.id) {mutableStateOf(false)}
    var lastPaymentCount by rememberSaveable(loan.id) {mutableStateOf(loan.payments.size)}
    var reversingId by rememberSaveable { mutableStateOf<String?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    var reversalError by remember { mutableStateOf<String?>(null) }
    val next = loan.installments().firstOrNull { it.remaining > 0 }
    val loanUrgency = dueUrgencyColors(next?.due, loan.remaining)
    LaunchedEffect(loan.payments.size) { if(lastPaymentCount!=loan.payments.size) {showPayment=false;lastPaymentCount=loan.payments.size} }
    LaunchedEffect(loan.reversals.size) { reversingId = null; reason = "" }
    var showSchedule by rememberSaveable(loan.id) {mutableStateOf(false)}
    var showLoanTools by rememberSaveable(loan.id) {mutableStateOf(false)}
    DetailDialog(onDismissRequest = dismiss, title = { Text(loan.party) }, accent = loanUrgency.accent, text = {
        LazyColumn(Modifier.fillMaxWidth().testTag("loan-details-list"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Remaining balance",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(loan.remaining),style=MaterialTheme.typography.headlineMedium)
                Text("${money(loan.total)} agreed total · ${loanUrgency.label}",style=MaterialTheme.typography.bodySmall,color=loanUrgency.accent)
            }}
            if(next!=null)item {
                Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Next installment · ${next.index+1} of ${loan.count}",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(money(next.remaining),style=MaterialTheme.typography.headlineSmall)
                        Text("Due ${next.due.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}",style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            saveError?.let {m->item {Text(m,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}}
            item {Disclosure("Installment schedule (${loan.count})",showSchedule,{showSchedule=!showSchedule})}
            if(showSchedule)items(loan.installments()) {inst->
                val urgency=dueUrgencyColors(inst.due,inst.remaining)
                Column {FinancialListRow(inst.due.toString(),"of ${money(inst.amount)} scheduled",inst.remaining,urgency.label,urgency.accent);HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)}
            }
            if(loan.payments.isNotEmpty())item {SectionLabel("Payment history")}
            items(loan.payments,key={it.id}) {payment->
                val reversal=loan.reversals.find {it.paymentId==payment.id}
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    MoneyLine(payment.date.toString(),payment.amount)
                    Text("Installment ${payment.installment+1}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(reversal!=null)Text("Reversed ${reversal.date}: ${reversal.reason}",style=MaterialTheme.typography.bodySmall)
                    else TextButton({reversingId=payment.id;reason="";reversalError=null},enabled=!busy,shape=MaterialTheme.shapes.small){
                        PilotIcon("Refresh", Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reverse payment")
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item {Disclosure("Manage loan",showLoanTools,{showLoanTools=!showLoanTools})}
            if(showLoanTools)item {Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(edit,enabled=!busy,modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium){
                    PilotIcon("Edit", Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit loan")
                }
                LoanPreferences(loan,finance)
                if(loan.payments.isEmpty()&&loan.reversals.isEmpty())TextButton({confirmDelete=true},enabled=!busy,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){
                    PilotIcon("Trash", Modifier.size(16.dp), tint=MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete loan")
                }
                else Text("Payment history is protected. Reverse an incorrect payment or archive this loan instead of deleting it.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
        }
    }, confirmButton = {
        if(next!=null)Button({showPayment=true},enabled=!busy,shape=MaterialTheme.shapes.medium){
            PilotIcon("Plus", Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if(loan.mode==LoanMode.I_OWE)"Record payment" else "Record received payment")
        }
    }, dismissButton = { TextButton(dismiss, enabled = !busy,shape=MaterialTheme.shapes.small) {
        PilotIcon("Close", Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Close")
    } })
    if(showPayment&&next!=null)LoanPaymentDialog(loan,busy,prefillFull,saveError,{showPayment=false},finance,save)
    if(confirmDelete)PilotModal(onDismissRequest={if(!busy)confirmDelete=false},title={Text("Delete ${loan.party}?")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Remove this loan and its unpaid schedule? This cannot be undone. No payment history will be deleted.")
        Text("If cloud sync is enabled, deletion is queued for your other devices. A small deletion marker prevents it from returning.",style=MaterialTheme.typography.bodySmall)
        saveError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={Button({delete()},enabled=!busy&&loan.payments.isEmpty()&&loan.reversals.isEmpty(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){
        PilotIcon("Trash", Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if(busy)"Deleting…" else "Delete this loan")
    }},dismissButton={TextButton({confirmDelete=false},enabled=!busy){Text("Keep loan")}})
    loan.payments.find { it.id == reversingId }?.let { payment ->
        PilotModal(
            onDismissRequest = { if (!busy) reversingId = null },
            category = "LEDGER REVERSAL",
            icon = "Loans",
            accent = MaterialTheme.colorScheme.error,
            title = { Text("Reverse payment?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This restores ${money(payment.amount)} to the outstanding balance. The original payment stays in history.")
                    OutlinedTextField(reason, { reason = it.take(500) }, label = { Text("Reason") }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, singleLine = true, enabled = !busy)
                    if (reversalError != null) Text(reversalError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (saveError != null) Text(saveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(enabled = !busy && reason.isNotBlank(), shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
                val today = LocalDate.now()
                if (today.isBefore(payment.date)) reversalError = "Your device date is earlier than this payment. Check your date settings before reversing it."
                else save(loan.reverse(payment.id, today, reason))
            }) { Text(if (busy) "Saving…" else "Confirm reversal") } },
            dismissButton = { TextButton({ reversingId = null }, enabled = !busy,shape=MaterialTheme.shapes.small) { Text("Cancel") } }
        )
    }
}
