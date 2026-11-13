package com.example.casha.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.casha.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

enum class SpendBand { GREEN, YELLOW, RED, UNSET }
internal fun safeSpendBand(safe:Long,funds:Long,active:Boolean=true):SpendBand {
    if(!active||funds<=0)return SpendBand.UNSET
    val remaining=BigDecimal.valueOf(safe).multiply(BigDecimal(100))
    val base=BigDecimal.valueOf(funds)
    return when {remaining>base.multiply(BigDecimal(50))->SpendBand.GREEN;remaining>=base.multiply(BigDecimal(20))->SpendBand.YELLOW;else->SpendBand.RED}
}
/** Incoming funding only, net of reversals. Expense refunds restore spent cash rather than inflate funding. Excludes designated savings accounts. */
internal fun cutoffFunding(
    profile: PaydayProfile,
    loans: List<Loan>,
    entries: List<CashEntry>,
    today: LocalDate,
    savingsAccountNames: Set<String> = emptySet()
): Long {
    fun inside(date:LocalDate)=!date.isBefore(profile.start)&&date.isBefore(profile.next)&&!date.isAfter(today)
    val spendable=entries.filter {it.channel !in savingsAccountNames && !it.isWalletTransfer}
    val income=spendable.filter {inside(it.date)}.sumOf {entry->
        if(entry.id.startsWith("undo_")) {
            val original=spendable.find {it.id==entry.id.removePrefix("undo_")}
            if(original?.income==true)-entry.amount else 0L
        } else if(entry.income)entry.amount else 0L
    }
    val collections=loans.filter {it.mode==LoanMode.OWED_TO_ME}.sumOf {loan->
        loan.payments.filter {inside(it.date)}.sumOf {it.amount}-loan.reversals.filter {inside(it.date)}.sumOf {r->loan.payments.first {it.id==r.paymentId}.amount}
    }
    return profile.salary+income+collections
}
@Composable internal fun PaydayPanel(title:String,subtitle:String?=null,content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                subtitle?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            content()
        }
    }
}
@Composable internal fun PaydayTotal(label:String,amount:Long,caption:String?=null) {
    Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(money(amount),style=MaterialTheme.typography.headlineSmall)
            caption?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}
@Composable internal fun SafeSpendCard(safe:Long,daily:Long,funds:Long,active:Boolean,nextLabel:String) {
    val band=safeSpendBand(safe,funds,active)
    val dark=MaterialTheme.colorScheme.background.luminance()<0.5f
    val (background,foreground,accent)=when(band) {
        SpendBand.GREEN->if(dark)Triple(Color(0xFF143126),Color(0xFFD6F5E1),Color(0xFF86CFA1)) else Triple(Color(0xFFECF8EF),Color(0xFF14532D),Color(0xFF15803D))
        SpendBand.YELLOW->if(dark)Triple(Color(0xFF332D12),Color(0xFFFFF1AD),Color(0xFFFACC15)) else Triple(Color(0xFFFFF9DB),Color(0xFF713F12),Color(0xFFA16207))
        SpendBand.RED->if(dark)Triple(Color(0xFF351D24),Color(0xFFFECACA),Color(0xFFF2A5A5)) else Triple(Color(0xFFFFF0F0),Color(0xFF7F1D1D),Color(0xFFB91C1C))
        SpendBand.UNSET->Triple(MaterialTheme.colorScheme.surface,MaterialTheme.colorScheme.onSurface,MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val percent=if(band==SpendBand.UNSET)null else BigDecimal.valueOf(safe).multiply(BigDecimal(100)).divide(BigDecimal.valueOf(funds),1,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    val shownPercent=when {band==SpendBand.GREEN&&percent?.toDouble()==50.0->">50";band==SpendBand.RED&&percent?.toDouble()==20.0->"<20";else->percent}
    val status=when(band){SpendBand.GREEN->"Comfortable";SpendBand.YELLOW->"Keep an eye on it";SpendBand.RED->"Low spending room";SpendBand.UNSET->"Budget needs an update"}
    Surface(Modifier.fillMaxWidth().tutorialTarget("safe-spend").semantics {contentDescription="Safe-to-spend status: ${band.name.lowercase()}"},shape=MaterialTheme.shapes.large,color=background,border=BorderStroke(1.dp,accent.copy(alpha=.35f))) {
        Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Safe to spend",style=MaterialTheme.typography.titleMedium,color=foreground)
            Text(money(safe),style=MaterialTheme.typography.headlineLarge,color=foreground)
            Text(if(percent==null)status else "$shownPercent% left · $status",style=MaterialTheme.typography.labelLarge,color=foreground)
            if(percent!=null)LinearProgressIndicator(progress={(safe.toDouble()/funds).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(6.dp),color=accent,trackColor=accent.copy(alpha=.14f))
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Daily allowance: ${money(daily)}",style=MaterialTheme.typography.bodyLarge,color=foreground)
                Text(nextLabel,style=MaterialTheme.typography.bodySmall,color=foreground)
            }
            Text(if(percent==null)"Set a current budget with positive funding to see the percentage." else "Of ${money(funds)} cutoff funds, after reserves.",style=MaterialTheme.typography.bodySmall,color=foreground)
        }
    }
}
