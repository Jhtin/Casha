package com.example.casha.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.casha.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable internal fun UpcomingLoanRow(loan:Loan,installment:Installment,inContainer:Boolean=false,onClick:()->Unit) {
    val today=LocalDate.now()
    val days=ChronoUnit.DAYS.between(today,installment.due)
    val status=when {days<0->"${-days} ${if(days == -1L)"day" else "days"} overdue";days==0L->"Due today";days==1L->"Due tomorrow";else->"Due in $days days"}
    val urgency=dueUrgencyColors(installment.due,installment.remaining,today)
    val tint=when {days<=3->MaterialTheme.colorScheme.errorContainer;days<=7->MaterialTheme.colorScheme.tertiaryContainer;else->MaterialTheme.colorScheme.surfaceContainerLow}
    val ink=when {days<=3->MaterialTheme.colorScheme.onErrorContainer;days<=7->MaterialTheme.colorScheme.onTertiaryContainer;else->MaterialTheme.colorScheme.onSurfaceVariant}

    val rowContent = @Composable {
        Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
            Surface(color=tint,shape=MaterialTheme.shapes.medium) {
                Column(Modifier.widthIn(min=48.dp).padding(horizontal=8.dp,vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text(installment.due.format(DateTimeFormatter.ofPattern("MMM")),style=MaterialTheme.typography.labelSmall,color=ink)
                    Text(installment.due.dayOfMonth.toString(),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,color=ink)
                    if(installment.due.year!=today.year)Text(installment.due.year.toString(),style=MaterialTheme.typography.labelSmall,color=ink)
                }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(loan.party,style=MaterialTheme.typography.titleSmall)
                Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(if(loan.mode==LoanMode.I_OWE)"To pay" else "To collect",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(money(installment.remaining),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                }
                Text(status,style=MaterialTheme.typography.labelMedium,color=if(days<=7)urgency.accent else MaterialTheme.colorScheme.onSurfaceVariant)
                if(installment.paid>0)Text("Partially paid",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PilotIcon("ChevronRight",Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if(inContainer) {
        Box(
            modifier=Modifier
                .fillMaxWidth()
                .clickable(onClick=onClick)
                .testTag("upcoming-${loan.id}")
        ) {
            rowContent()
        }
    } else {
        Surface(
            onClick=onClick,
            modifier=Modifier.fillMaxWidth().testTag("upcoming-${loan.id}"),
            shape=MaterialTheme.shapes.large,
            color=MaterialTheme.colorScheme.surface,
            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
        ) {
            rowContent()
        }
    }
}
