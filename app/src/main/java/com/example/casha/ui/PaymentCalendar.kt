package com.example.casha.ui

import com.example.casha.ui.theme.FinanceDesign
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.casha.domain.*
import com.example.casha.data.FinanceStore
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.toArgb
import java.time.temporal.ChronoUnit
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable fun PaymentCalendar(loans:List<Loan>,modifier:Modifier=Modifier,finance:FinanceStore?=null,openLoan:(String)->Unit) {
    val revision=finance?.changes?.collectAsState()?.value
    val profile=remember(revision){finance?.profile()}
    val cash=remember(revision){finance?.cashEntries().orEmpty()}
    var customize by remember {mutableStateOf(false)}
    var colorRevision by remember {mutableStateOf(0)}
    val palette=listOf("Green" to Color(0xFF15803D),"Red" to Color(0xFFB91C1C),"Blue" to Color(0xFF2563EB),"Purple" to Color(0xFF7E22CE),"Amber" to Color(0xFFB45309))
    fun marker(key:String,default:Color):Color { colorRevision;return Color(finance?.preferences?.getInt("calendar_color_$key",default.toArgb())?:default.toArgb()) }
    val paydayColor=marker("payday",palette[0].second)
    val today=LocalDate.now()
    var monthText by rememberSaveable {mutableStateOf(YearMonth.now().toString())}
    var selectedText by rememberSaveable {mutableStateOf(today.toString())}
    var unpaidOnly by rememberSaveable {mutableStateOf(true)}
    var latestFirst by rememberSaveable {mutableStateOf(true)}
    val month=YearMonth.parse(monthText)
    val selected=LocalDate.parse(selectedText)

    // Active loans: exclude loans that are fully paid (remaining <= 0) or archived
    val activeLoans=remember(loans,revision){loans.filter {it.remaining>0&&finance?.flag("archived_${it.id}")!=true}}

    // Visible loans for calendar scheduled entries:
    // When unpaidOnly is true: only active loans with outstanding dues
    // When unpaidOnly is false: unarchived loans including historical paid installments
    val visibleLoans=remember(loans,unpaidOnly,revision){loans.filter {finance?.flag("archived_${it.id}")!=true&&(!unpaidOnly||it.remaining>0)}}

    val entries=visibleLoans.flatMap {loan->loan.installments().map {loan to it}}
        .filter {YearMonth.from(it.second.due)==month&&(!unpaidOnly||it.second.remaining>0)}
    val byDay=entries.groupBy {it.second.due}
    val dayEntries=byDay[selected].orEmpty()
    fun moveMonth(offset:Long) {
        val next=month.plusMonths(offset)
        monthText=next.toString()
        selectedText=next.atDay(minOf(selected.dayOfMonth,next.lengthOfMonth())).toString()
    }

    LazyColumn(
        modifier=modifier.fillMaxSize().testTag("calendar-list"),
        contentPadding=PaddingValues(horizontal=FinanceDesign.PagePadding,vertical=16.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)
    ) {
        // 1. Sleek Header Row (No text walls!)
        item {
            Row(
                Modifier.fillMaxWidth().padding(top=2.dp,bottom=2.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween
            ) {
                Text(
                    "Payment calendar",
                    style=MaterialTheme.typography.headlineMedium,
                    fontWeight=FontWeight.Bold
                )
                if(finance!=null) {
                    FilledTonalIconButton(
                        onClick={customize=true},
                        modifier=Modifier.semantics {contentDescription="Calendar colors"}
                    ) {
                        PilotIcon("Appearance",Modifier.size(18.dp))
                    }
                }
            }
        }

        // 2. Unified Modern Calendar Card (Month Navigation, Jump to Today, Filters & Grid)
        item {
            Surface(
                modifier=Modifier.fillMaxWidth().tutorialTarget("calendar-card"),
                shape=MaterialTheme.shapes.large,
                color=MaterialTheme.colorScheme.surface,
                border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(horizontal=10.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    // Row 1: Month Navigation (Left) + Jump to Today (Right)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick={moveMonth(-1)},
                                modifier=Modifier.size(36.dp).semantics {contentDescription="Previous month"}
                            ) {
                                PilotIcon("ChevronLeft",Modifier.size(20.dp))
                            }
                            Text(
                                month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                                style=MaterialTheme.typography.titleMedium,
                                fontWeight=FontWeight.Bold
                            )
                            IconButton(
                                onClick={moveMonth(1)},
                                modifier=Modifier.size(36.dp).semantics {contentDescription="Next month"}
                            ) {
                                PilotIcon("ChevronRight",Modifier.size(20.dp))
                            }
                        }

                        val isCurrentDaySelected = month==YearMonth.from(today) && selected==today
                        FilledTonalButton(
                            onClick={
                                monthText=YearMonth.from(today).toString()
                                selectedText=today.toString()
                            },
                            shape=RoundedCornerShape(50),
                            colors=if(isCurrentDaySelected) {
                                ButtonDefaults.filledTonalButtonColors(
                                    containerColor=MaterialTheme.colorScheme.primaryContainer,
                                    contentColor=MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            },
                            contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp),
                            modifier=Modifier.defaultMinSize(minHeight=32.dp).semantics {contentDescription="Jump to today"}
                        ) {
                            PilotIcon("Today",Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Today",
                                style=MaterialTheme.typography.labelMedium,
                                fontWeight=FontWeight.SemiBold,
                                maxLines=1,
                                softWrap=false
                            )
                        }
                    }

                    // Row 2: View Filter (Left) + Dues in Month Badge (Right)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal=2.dp),
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.SpaceBetween
                    ) {
                        FilterChip(
                            selected=unpaidOnly,
                            onClick={unpaidOnly=!unpaidOnly},
                            leadingIcon={PilotIcon("Filter",Modifier.size(13.dp))},
                            label={Text(if(unpaidOnly)"Outstanding only" else "All scheduled dues",style=MaterialTheme.typography.labelSmall)},
                            shape=RoundedCornerShape(50)
                        )

                        val duesCount=entries.count {it.second.remaining>0}
                        Surface(
                            color=MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape=RoundedCornerShape(50),
                            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f))
                        ) {
                            Text(
                                if(duesCount>0) "$duesCount due" else "No dues",
                                modifier=Modifier.padding(horizontal=10.dp,vertical=3.dp),
                                style=MaterialTheme.typography.labelSmall,
                                color=if(duesCount>0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f))

                    // Weekday headers: Mon, Tue, Wed, Thu, Fri, Sat, Sun
                    Row(Modifier.fillMaxWidth()) {
                        listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun").forEach {day->
                            Box(Modifier.weight(1f).height(24.dp),contentAlignment=Alignment.Center) {
                                Text(
                                    day,
                                    style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.75f),
                                    fontWeight=FontWeight.Medium
                                )
                            }
                        }
                    }

                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))

                    val offset=month.atDay(1).dayOfWeek.value-1
                    val rows=(offset+month.lengthOfMonth()+6)/7
                    repeat(rows) {week->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            repeat(7) {weekday->
                                val day=week*7+weekday-offset+1
                                if(day !in 1..month.lengthOfMonth()) {
                                    Spacer(Modifier.weight(1f).height(54.dp))
                                } else {
                                    val date=month.atDay(day)
                                    val due=byDay[date].orEmpty()
                                    val chosen=date==selected
                                    val isToday=date==today
                                    val isPayday=date==profile?.start||date==profile?.next
                                    val days=ChronoUnit.DAYS.between(today,date)
                                    val hasDue=due.isNotEmpty()
                                    val outstanding=due.any {it.second.remaining>0}
                                    val totalRemainingOnDay=due.sumOf {it.second.remaining}

                                    // Color tint for the cell
                                    val cellBg=when {
                                        chosen -> MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.45f)
                                        isToday -> MaterialTheme.colorScheme.primary.copy(alpha=0.08f)
                                        outstanding&&days<=3 -> palette[1].second.copy(alpha=0.12f)
                                        outstanding&&days<=7 -> Color(0xFFEAB308).copy(alpha=0.12f)
                                        outstanding -> due.firstOrNull()?.let {marker(it.first.id,palette[1].second)}?.copy(alpha=0.10f) ?: MaterialTheme.colorScheme.surface
                                        isPayday -> paydayColor.copy(alpha=0.12f)
                                        hasDue -> MaterialTheme.colorScheme.secondary.copy(alpha=0.08f)
                                        else -> Color.Transparent
                                    }

                                    val cellBorder=when {
                                        chosen -> BorderStroke(2.dp,MaterialTheme.colorScheme.primary)
                                        isToday -> BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=0.6f))
                                        else -> BorderStroke(0.5.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
                                    }

                                    val hasActivity=cash.any {it.date==date}||loans.any {l->l.payments.any {it.date==date}||l.reversals.any {it.date==date}}

                                    Surface(
                                        onClick={selectedText=date.toString()},
                                        modifier=Modifier
                                            .weight(1f)
                                            .height(54.dp)
                                            .testTag("calendar-day-$date")
                                            .semantics {
                                                contentDescription=buildString {
                                                    append("${date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))}.")
                                                    if(isToday) append(" Today.")
                                                    if(isPayday) append(" Payday.")
                                                    if(outstanding) append(" Due ${money(totalRemainingOnDay)}.")
                                                    if(due.isNotEmpty()) append(" Loans: ${due.map {it.first.party}.distinct().joinToString(", ")}.")
                                                    val count=cash.count {it.date==date}+loans.sumOf {loan->loan.payments.count {it.date==date}+loan.reversals.count {it.date==date}}
                                                    if(count>0) append(" $count recorded activities.")
                                                }
                                            },
                                        shape=RoundedCornerShape(8.dp),
                                        color=cellBg,
                                        border=cellBorder
                                    ) {
                                        Column(
                                            Modifier.fillMaxSize().padding(horizontal=2.dp,vertical=4.dp),
                                            horizontalAlignment=Alignment.CenterHorizontally,
                                            verticalArrangement=Arrangement.SpaceBetween
                                        ) {
                                            // Top: Day number (with Today pill if today)
                                            if(isToday) {
                                                Box(
                                                    Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                                    contentAlignment=Alignment.Center
                                                ) {
                                                    Text(
                                                        day.toString(),
                                                        style=MaterialTheme.typography.labelSmall,
                                                        color=MaterialTheme.colorScheme.onPrimary,
                                                        fontWeight=FontWeight.Bold
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    day.toString(),
                                                    style=MaterialTheme.typography.labelMedium,
                                                    color=if(chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                    fontWeight=if(chosen) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }

                                            // Bottom indicators: Payday mark, dues dots, activity dot
                                            Row(
                                                horizontalArrangement=Arrangement.spacedBy(3.dp),
                                                verticalAlignment=Alignment.CenterVertically,
                                                modifier=Modifier.padding(bottom=2.dp)
                                            ) {
                                                if(isPayday) {
                                                    Box(Modifier.size(6.dp).clip(CircleShape).background(paydayColor))
                                                }
                                                if(outstanding) {
                                                    val distinctLoans=due.filter {it.second.remaining>0}.distinctBy {it.first.id}
                                                    distinctLoans.take(2).forEach {(loan,_)->
                                                        val dotColor=when {
                                                            days<=3 -> palette[1].second
                                                            days<=7 -> Color(0xFFEAB308)
                                                            else -> marker(loan.id,palette[1].second)
                                                        }
                                                        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                                                    }
                                                    if(distinctLoans.size>2) {
                                                        Text("+${distinctLoans.size-2}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                } else if(hasDue) {
                                                    Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                                                }
                                                if(hasActivity) {
                                                    Box(Modifier.size(3.5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.5f)))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Monthly High-Level Summary Metrics
        item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                PaydayMetric(
                    label="Monthly to pay",
                    amount=entries.filter {it.first.mode==LoanMode.I_OWE}.sumOf {it.second.remaining},
                    icon="Loans",
                    accent=MaterialTheme.colorScheme.error,
                    modifier=Modifier.weight(1f)
                )
                PaydayMetric(
                    label="Monthly to collect",
                    amount=entries.filter {it.first.mode==LoanMode.OWED_TO_ME}.sumOf {it.second.remaining},
                    icon="Payday",
                    accent=MaterialTheme.colorScheme.secondary,
                    modifier=Modifier.weight(1f)
                )
            }
        }

        // 5. Day Inspector: Clear, readable details table for selected date
        item {
            Surface(
                modifier=Modifier.fillMaxWidth().testTag("day-inspector"),
                shape=MaterialTheme.shapes.large,
                color=MaterialTheme.colorScheme.surface,
                border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    // Header of the selected day
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(
                                selected.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                                style=MaterialTheme.typography.titleMedium,
                                fontWeight=FontWeight.Bold
                            )
                            if(selected==today) {
                                Text("Today",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                            }
                        }

                        // Badges
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(selected==profile?.start||selected==profile?.next) {
                                Surface(
                                    color=paydayColor.copy(alpha=0.15f),
                                    shape=RoundedCornerShape(50),
                                    border=BorderStroke(1.dp,paydayColor.copy(alpha=0.4f))
                                ) {
                                    Text(
                                        "Payday Cutoff",
                                        modifier=Modifier.padding(horizontal=8.dp,vertical=3.dp),
                                        style=MaterialTheme.typography.labelSmall,
                                        color=paydayColor
                                    )
                                }
                            }
                            if(dayEntries.isNotEmpty()) {
                                val totalDayRemaining=dayEntries.sumOf {it.second.remaining}
                                val badgeColor=if(totalDayRemaining>0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                                Surface(
                                    color=badgeColor.copy(alpha=0.15f),
                                    shape=RoundedCornerShape(50),
                                    border=BorderStroke(1.dp,badgeColor.copy(alpha=0.4f))
                                ) {
                                    Text(
                                        if(totalDayRemaining>0) "${dayEntries.size} due · ${money(totalDayRemaining)}" else "All paid",
                                        modifier=Modifier.padding(horizontal=8.dp,vertical=3.dp),
                                        style=MaterialTheme.typography.labelSmall,
                                        color=badgeColor
                                    )
                                }
                            }
                        }
                    }

                    // Scheduled Dues Table
                    if(dayEntries.isNotEmpty()) {
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f))
                        Text("Scheduled Dues",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.fillMaxWidth()) {
                            dayEntries.forEachIndexed {index,(loan,installment)->
                                val urgency=dueUrgencyColors(installment.due,installment.remaining,today)
                                val isFullyPaid=installment.remaining==0L
                                Row(
                                    modifier=Modifier
                                        .fillMaxWidth()
                                        .clickable {openLoan(loan.id)}
                                        .testTag("calendar-loan-${loan.id}")
                                        .padding(vertical=10.dp,horizontal=4.dp),
                                    verticalAlignment=Alignment.CenterVertically,
                                    horizontalArrangement=Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f).padding(end=10.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                        Text(loan.party,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                                        Text(
                                            "${if(loan.mode==LoanMode.I_OWE)"To pay" else "To collect"} · Installment ${installment.index+1} of ${loan.count}",
                                            style=MaterialTheme.typography.bodySmall,
                                            color=MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            money(if(isFullyPaid) installment.amount else installment.remaining),
                                            style=MaterialTheme.typography.titleSmall,
                                            fontWeight=FontWeight.Bold,
                                            color=if(isFullyPaid) MaterialTheme.colorScheme.secondary else if(loan.mode==LoanMode.I_OWE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                        Surface(
                                            color=(if(isFullyPaid) MaterialTheme.colorScheme.secondary else urgency.accent).copy(alpha=0.12f),
                                            shape=RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                if(isFullyPaid)"Paid" else if(installment.paid>0)"Partially paid" else urgency.label,
                                                style=MaterialTheme.typography.labelSmall,
                                                color=if(isFullyPaid) MaterialTheme.colorScheme.secondary else urgency.accent,
                                                modifier=Modifier.padding(horizontal=6.dp,vertical=1.dp)
                                            )
                                        }
                                    }
                                }
                                if(index < dayEntries.lastIndex) {
                                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
                                }
                            }
                        }
                    }

                    // Recorded Activity Table
                    val dayCash=cash.filter {it.date==selected}
                    val payments=loans.flatMap {loan->loan.payments.filter {it.date==selected}.map {loan to it}}
                    val reversals=loans.flatMap {loan->loan.reversals.filter {it.date==selected}.map {loan to it}}
                    val hasRecordedActivity=dayCash.isNotEmpty()||payments.isNotEmpty()||reversals.isNotEmpty()

                    if(hasRecordedActivity) {
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.SpaceBetween
                        ) {
                            Text("Recorded Activity",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(
                                color=MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape=RoundedCornerShape(50),
                                modifier=Modifier
                                    .clickable {latestFirst=!latestFirst}
                                    .semantics {contentDescription="Sort activities: currently ${if(latestFirst)"Latest first" else "Oldest first"}"}
                            ) {
                                Row(
                                    modifier=Modifier.padding(horizontal=8.dp,vertical=3.dp),
                                    verticalAlignment=Alignment.CenterVertically,
                                    horizontalArrangement=Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        if(latestFirst)"Latest first" else "Oldest first",
                                        style=MaterialTheme.typography.labelSmall,
                                        color=MaterialTheme.colorScheme.primary,
                                        fontWeight=FontWeight.Medium
                                    )
                                }
                            }
                        }
                        Column(Modifier.fillMaxWidth()) {
                            val allActivities = mutableListOf<DayRecordedActivity>()
                            dayCash.forEach { allActivities.add(DayRecordedActivity.Cash(it)) }
                            payments.forEach { (loan,payment) -> allActivities.add(DayRecordedActivity.LoanPayment(loan, payment)) }
                            reversals.forEach { (loan,reversal) -> allActivities.add(DayRecordedActivity.LoanReversal(loan, reversal)) }

                            val orderedActivities = if(latestFirst) allActivities.reversed() else allActivities

                            orderedActivities.forEachIndexed {index,activity->
                                when(activity) {
                                    is DayRecordedActivity.Cash -> {
                                        val entry = activity.entry
                                        val isReversal = entry.id.startsWith("undo_")
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical=8.dp,horizontal=4.dp),
                                            verticalAlignment=Alignment.CenterVertically,
                                            horizontalArrangement=Arrangement.SpaceBetween
                                        ) {
                                            Column(Modifier.weight(1f).padding(end=10.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    if(isReversal)"Cash reversal" else entry.note.ifBlank {if(entry.income)"Income" else "Expense"},
                                                    style=MaterialTheme.typography.bodyMedium,
                                                    fontWeight=FontWeight.Medium
                                                )
                                                Text(
                                                    "${if(entry.income)"Income" else "Expense"}${if(entry.channel.isNotBlank()) " · ${entry.channel}" else ""}",
                                                    style=MaterialTheme.typography.bodySmall,
                                                    color=MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                "${if(entry.income) "+" else "-"}${money(entry.amount)}",
                                                style=MaterialTheme.typography.bodyMedium,
                                                fontWeight=FontWeight.SemiBold,
                                                color=if(entry.income) palette[0].second else MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    is DayRecordedActivity.LoanPayment -> {
                                        val loan = activity.loan
                                        val payment = activity.payment
                                        Row(
                                            Modifier.fillMaxWidth().clickable {openLoan(loan.id)}.padding(vertical=8.dp,horizontal=4.dp),
                                            verticalAlignment=Alignment.CenterVertically,
                                            horizontalArrangement=Arrangement.SpaceBetween
                                        ) {
                                            val link = finance?.paymentLink(payment.id)
                                            val channelLabel = if(link != null) " · ${link.accountName}" else ""
                                            Column(Modifier.weight(1f).padding(end=10.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                                Text(loan.party,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
                                                Text("Recorded payment · Inst. ${payment.installment+1}$channelLabel",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Text(
                                                "${if(loan.mode==LoanMode.I_OWE) "-" else "+"}${money(payment.amount)}",
                                                style=MaterialTheme.typography.bodyMedium,
                                                fontWeight=FontWeight.SemiBold,
                                                color=MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    is DayRecordedActivity.LoanReversal -> {
                                        val loan = activity.loan
                                        val reversal = activity.reversal
                                        val origPayment=loan.payments.firstOrNull {it.id==reversal.paymentId}
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical=8.dp,horizontal=4.dp),
                                            verticalAlignment=Alignment.CenterVertically,
                                            horizontalArrangement=Arrangement.SpaceBetween
                                        ) {
                                            Column(Modifier.weight(1f).padding(end=10.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                                Text("Payment reversal · ${loan.party}",style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
                                                Text(reversal.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            origPayment?.let {
                                                Text(money(it.amount),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                                if(index < orderedActivities.lastIndex) {
                                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
                                }
                            }
                        }
                    }

                    // Empty state if neither dues nor activity
                    if(dayEntries.isEmpty()&&!hasRecordedActivity) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical=12.dp),
                            horizontalArrangement=Arrangement.Center,
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Text(
                                "No scheduled dues or recorded activity on this date",
                                style=MaterialTheme.typography.bodyMedium,
                                color=MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal dialog for colors
    if(customize&&finance!=null)PilotModal(onDismissRequest={customize=false},title={Text("Calendar colors")},category="Personalization",icon="Appearance",accent=MaterialTheme.colorScheme.primary,text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Customize visual colors for your payday markers and active loans.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)

            val targets=listOf("payday" to "Payday")+activeLoans.map {it.id to it.party}

            targets.forEach {(key,label)->
                val isPayday=key=="payday"
                val defaultColor=if(isPayday)palette[0].second else palette[1].second
                val currentColor=marker(key,defaultColor)

                Surface(
                    color=MaterialTheme.colorScheme.surfaceContainerLow,
                    shape=MaterialTheme.shapes.medium,
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f)),
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            Text(label,style=MaterialTheme.typography.titleSmall)
                            Surface(
                                color=currentColor.copy(alpha=0.15f),
                                shape=RoundedCornerShape(50),
                                border=BorderStroke(1.dp,currentColor.copy(alpha=0.4f))
                            ) {
                                Row(
                                    Modifier.padding(horizontal=8.dp,vertical=2.dp),
                                    verticalAlignment=Alignment.CenterVertically,
                                    horizontalArrangement=Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(currentColor))
                                    Text(
                                        if(isPayday)"Cutoff" else "Active loan",
                                        style=MaterialTheme.typography.labelSmall,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Circular visual color buttons (no text chips)
                        Row(
                            modifier=Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.SpaceBetween,
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            palette.forEach {(colorName,color)->
                                val isSelected=currentColor==color
                                Box(
                                    modifier=Modifier
                                        .size(42.dp)
                                        .semantics {
                                            contentDescription="$label: $colorName color"
                                            role=Role.RadioButton
                                        }
                                        .border(
                                            BorderStroke(
                                                if(isSelected) 2.5.dp else 1.dp,
                                                if(isSelected) MaterialTheme.colorScheme.primary else color.copy(alpha=0.35f)
                                            ),
                                            CircleShape
                                        )
                                        .padding(3.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .clickable {
                                            finance.preferences.edit().putInt("calendar_color_$key",color.toArgb()).apply()
                                            colorRevision++
                                        },
                                    contentAlignment=Alignment.Center
                                ) {
                                    if(isSelected) {
                                        PilotIcon("Check",Modifier.size(16.dp),tint=Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if(activeLoans.isEmpty()) {
                Surface(
                    color=MaterialTheme.colorScheme.surfaceContainerLow,
                    shape=MaterialTheme.shapes.medium,
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f)),
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("No active loans",style=MaterialTheme.typography.titleSmall)
                        Text("Fully paid and archived loans are excluded. When you add or have active loans, their color pickers will appear here.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    },confirmButton={Button({customize=false},shape=MaterialTheme.shapes.medium){
        PilotIcon("Check",Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Done")
    }})
}

private sealed class DayRecordedActivity {
    data class Cash(val entry: CashEntry) : DayRecordedActivity()
    data class LoanPayment(val loan: Loan, val payment: Payment) : DayRecordedActivity()
    data class LoanReversal(val loan: Loan, val reversal: PaymentReversal) : DayRecordedActivity()
}
