package com.example.casha.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.casha.data.FinanceStore
import com.example.casha.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal fun cashActivityEntries(entries:List<CashEntry>,profile:PaydayProfile,allHistory:Boolean,query:String="",today:LocalDate=LocalDate.now()):List<CashEntry> {
    val search=query.trim()
    return entries.withIndex().filter {(_,entry)->
        (allHistory||(!entry.date.isBefore(profile.start)&&entry.date.isBefore(profile.next)&&!entry.date.isAfter(today)))&&
            (search.isEmpty()||entry.note.contains(search,true)||entry.channel.contains(search,true)||entry.date.toString().contains(search,true)||entry.date.format(DateTimeFormatter.ofPattern("MMM d, yyyy")).contains(search,true))
    }.sortedWith(compareByDescending<IndexedValue<CashEntry>> {it.value.date}.thenByDescending {it.index}).map {it.value}
}

@Composable internal fun CashActivityRow(
    entry:CashEntry,
    entries:List<CashEntry>,
    finance:FinanceStore,
    onEdit:(CashEntry)->Unit={},
    reverse:(CashEntry)->Unit
) {
    val isReversal=entry.id.startsWith("undo_")
    val isReversed=entries.any {it.id=="undo_${entry.id}"}
    val isSavings=finance.savingsAccountNames().contains(entry.channel)
    val accounts=remember(finance.changes.collectAsState().value){finance.allMoneyAccounts()}
    val account=accounts.find {it.name==entry.channel}
    val channelIcon=account?.icon?:if(isSavings)"Savings" else if(entry.income)"Plus" else "Cash"
    val canReverse=finance.canReverseCash(entry.id)

    val cleanNote=if(isReversal) {
        val stripped=entry.note.removePrefix("Reversal:").removePrefix("Reversal").trim()
        if(stripped.isNotBlank()) "Reversal: $stripped" else "Reversal of entry"
    } else {
        entry.note.ifBlank { if(entry.income) "Income" else "Expense" }
    }

    val today=LocalDate.now()
    val dateLabel=when(entry.date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> entry.date.format(DateTimeFormatter.ofPattern("MMM d"))
    }

    val avatarIcon=when {
        isReversal -> "Refresh"
        isReversed -> "Close"
        else -> channelIcon
    }

    val (avatarBg,avatarTint)=when {
        isReversed -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f)
        isReversal -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha=0.7f) to MaterialTheme.colorScheme.onTertiaryContainer
        entry.income -> Color(0xFF15803D).copy(alpha=0.15f) to Color(0xFF15803D)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha=0.35f) to MaterialTheme.colorScheme.error
    }

    Surface(
        color=Color.Transparent,
        modifier=Modifier
            .fillMaxWidth()
            .then(if(canReverse && !entry.isWalletTransfer && !entry.id.startsWith("atm_fee_")) Modifier.clickable {onEdit(entry)} else Modifier)
            .padding(vertical=6.dp,horizontal=2.dp)
    ) {
        Row(
            modifier=Modifier.fillMaxWidth(),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier=Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(avatarBg),
                contentAlignment=Alignment.Center
            ) {
                PilotIcon(avatarIcon,modifier=Modifier.size(18.dp),tint=avatarTint)
            }

            Column(
                modifier=Modifier.weight(1f),
                verticalArrangement=Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text=cleanNote,
                    style=MaterialTheme.typography.titleSmall,
                    fontWeight=if(isReversed) FontWeight.Normal else FontWeight.SemiBold,
                    textDecoration=if(isReversed) TextDecoration.LineThrough else TextDecoration.None,
                    color=if(isReversed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f) else MaterialTheme.colorScheme.onSurface,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text="$dateLabel · ${entry.channel}${if(isSavings) " (Savings)" else ""}",
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.weight(1f, fill=false)
                    )

                    if(isReversed) {
                        Surface(
                            color=MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape=RoundedCornerShape(50),
                            border=BorderStroke(0.5.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.5f))
                        ) {
                            Text(
                                "Reversed",
                                modifier=Modifier.padding(horizontal=6.dp,vertical=1.dp),
                                style=MaterialTheme.typography.labelSmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines=1,
                                softWrap=false
                            )
                        }
                    } else if(isReversal) {
                        Surface(
                            color=MaterialTheme.colorScheme.tertiaryContainer.copy(alpha=0.5f),
                            shape=RoundedCornerShape(50),
                            border=BorderStroke(0.5.dp,MaterialTheme.colorScheme.tertiary.copy(alpha=0.4f))
                        ) {
                            Text(
                                "Correction",
                                modifier=Modifier.padding(horizontal=6.dp,vertical=1.dp),
                                style=MaterialTheme.typography.labelSmall,
                                color=MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines=1,
                                softWrap=false
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(4.dp)
            ) {
                val amountText=when {
                    isReversed -> money(entry.amount)
                    isReversal -> "${if(entry.income) "+" else "-"}${money(entry.amount)}"
                    entry.income -> "+${money(entry.amount)}"
                    else -> "-${money(entry.amount)}"
                }

                val amountColor=when {
                    isReversed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f)
                    isReversal -> if(entry.income) Color(0xFF15803D) else MaterialTheme.colorScheme.error
                    entry.income -> Color(0xFF15803D)
                    else -> MaterialTheme.colorScheme.onSurface
                }

                Text(
                    text=amountText,
                    style=MaterialTheme.typography.titleSmall,
                    fontWeight=FontWeight.Bold,
                    textDecoration=if(isReversed) TextDecoration.LineThrough else TextDecoration.None,
                    color=amountColor
                )

                if(canReverse) {
                    if (!entry.isWalletTransfer && !entry.id.startsWith("atm_fee_")) IconButton(
                        onClick={onEdit(entry)},
                        modifier=Modifier
                            .size(32.dp)
                            .semantics {contentDescription="Edit cash entry"}
                    ) {
                        PilotIcon("Edit",modifier=Modifier.size(15.dp),tint=MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick={reverse(entry)},
                        modifier=Modifier
                            .size(32.dp)
                            .semantics {
                                contentDescription="Reverse cash entry"
                                text=AnnotatedString("Reverse cash entry")
                            }
                    ) {
                        PilotIcon("Refresh",modifier=Modifier.size(15.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable internal fun CashActivityHistory(entries:List<CashEntry>,profile:PaydayProfile,finance:FinanceStore,initialQuery:String="",dismiss:()->Unit,onEdit:(CashEntry)->Unit={},reverse:(CashEntry)->Unit) {
    var allHistory by rememberSaveable {mutableStateOf(true)}
    var query by rememberSaveable {mutableStateOf(initialQuery)}
    var page by rememberSaveable {mutableIntStateOf(0)}
    var accountFilter by rememberSaveable {mutableStateOf("All")}
    val accounts=remember(finance.changes.collectAsState().value){finance.allMoneyAccounts()}
    val searched=remember(entries,profile,allHistory,query){cashActivityEntries(entries,profile,allHistory,query)}
    val filtered=remember(searched,accountFilter){if(accountFilter=="All") searched else searched.filter {it.channel==accountFilter}}
    val pages=((filtered.size+9)/10).coerceAtLeast(1)
    val current=page.coerceIn(0,pages-1)
    val listState=rememberLazyListState()
    LaunchedEffect(current,allHistory,accountFilter){listState.scrollToItem(0)}
    DetailDialog(onDismissRequest=dismiss,title={Text("Activity history")},eyebrow="Income & expenses",text={
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1f,fill=false).fillMaxWidth().testTag("cash-history-list"),state=listState,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {SegmentedToggle(listOf("This payday","All history"),if(allHistory)1 else 0,{allHistory=it==1;page=0})}
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected=accountFilter=="All",
                            onClick={accountFilter="All";page=0},
                            label={Text("All accounts")}
                        )
                        accounts.forEach {acc->
                            FilterChip(
                                selected=accountFilter==acc.name,
                                onClick={accountFilter=acc.name;page=0},
                                label={Text(if(acc.isArchived)"${acc.name} (Archived)" else acc.name)},
                                leadingIcon={PilotIcon(if(acc.isArchived)"Archive" else acc.icon,Modifier.size(14.dp))}
                            )
                        }
                    }
                }
                item {OutlinedTextField(
                    query,
                    {query=it.take(100);page=0},
                    label={Text("Search note, account, or date")},
                    placeholder={Text("e.g. Food, GCash, Bank or 2026-11")},
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth(),
                    leadingIcon={PilotIcon("Search",Modifier.size(18.dp))},
                    trailingIcon={if(query.isNotEmpty())IconButton({query="";page=0},modifier=Modifier.semantics {contentDescription="Clear search"}){PilotIcon("Close",Modifier.size(16.dp))}}
                )}
                item {Text("${filtered.size} entries · newest first · 10 per page",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(filtered.isEmpty())item {Text(if(query.isBlank()&&accountFilter=="All")"No activity in this period." else "No matching entries. Try another search or account filter.")}
                items(filtered.drop(current*10).take(10),key={it.id}) {entry->CashActivityRow(entry,entries,finance,onEdit,reverse)}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                IconButton({page=current-1},enabled=current>0,modifier=Modifier.semantics {contentDescription="Previous activity page"}){PilotIcon("ChevronLeft",Modifier.size(20.dp))}
                Text("Page ${current+1} of $pages",style=MaterialTheme.typography.labelLarge)
                IconButton({page=current+1},enabled=current<pages-1,modifier=Modifier.semantics {contentDescription="Next activity page"}){PilotIcon("ChevronRight",Modifier.size(20.dp))}
            }
        }
    },confirmButton={TextButton(dismiss){PilotIcon("Close",Modifier.size(14.dp));Spacer(Modifier.width(4.dp));Text("Close history")}},dismissButton={})
}
