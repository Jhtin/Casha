package com.example.casha.ui

import com.example.casha.ui.theme.FinanceDesign
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal data class UrgencyColors(
    val accent: Color,
    val badgeBg: Color,
    val badgeFg: Color,
    val cardGradientStart: Color,
    val cardGradientEnd: Color,
    val border: Color,
    val label: String
)

@Composable
internal fun dueUrgencyColors(due:LocalDate?,remaining:Long,today:LocalDate=LocalDate.now()):UrgencyColors {
    val colors=MaterialTheme.colorScheme
    val days=due?.let {ChronoUnit.DAYS.between(today,it)}?:Long.MAX_VALUE
    val (color,label)=when {
        remaining<=0||due==null -> colors.secondary to "Paid"
        days<0 -> colors.error to "Overdue"
        days==0L -> colors.error to "Due today"
        days<=3 -> colors.error to "Due in ${days}d"
        days<=7 -> colors.tertiary to "Due in ${days}d"
        else -> colors.onSurfaceVariant to "Upcoming"
    }
    return UrgencyColors(color,colors.surfaceContainerLow,color,colors.surface,colors.surface,colors.outlineVariant,label)
}
internal fun categoryColor(category:String):Color=com.example.casha.ui.theme.FinanceDesign.Blue
@Composable internal fun UrgencyLegendRow() {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("Overdue" to MaterialTheme.colorScheme.error,"Due soon" to MaterialTheme.colorScheme.tertiary,"Paid" to MaterialTheme.colorScheme.secondary).forEach {(label,color)->
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {Box(Modifier.size(5.dp).clip(CircleShape).background(color));Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}

/** Clean, architectural vector icons with uniform 1.8dp stroke weight. */
@Composable internal fun PilotIcon(name:String, modifier:Modifier=Modifier, tint:Color=LocalContentColor.current) {
    if (name == "Petot") {
        PetotCharacter(PetotMood.HELLO, false, modifier.size(24.dp))
        return
    }

    Canvas(modifier.size(22.dp)) {
        val s=size.width/24f
        val stroke=Stroke(1.8f*s,cap=StrokeCap.Round)
        fun line(x:Float,y:Float,a:Float,b:Float)=drawLine(tint,Offset(x*s,y*s),Offset(a*s,b*s),1.8f*s,StrokeCap.Round)
        fun path(vararg points:Float) { val p=Path();p.moveTo(points[0]*s,points[1]*s);for(i in 2 until points.size step 2)p.lineTo(points[i]*s,points[i+1]*s);drawPath(p,tint,style=stroke) }
        when(name) {
            "Loans" -> {
                drawRoundRect(tint,Offset(3*s,5*s),Size(18*s,14*s),CornerRadius(2.5f*s),style=stroke)
                line(3f,10f,21f,10f)
                line(7f,15f,11f,15f)
            }
            "Payday" -> {
                drawRoundRect(tint,Offset(3.5f,4.5f)*s,Size(17*s,16*s),CornerRadius(2.5f*s),style=stroke)
                line(3.5f,9.5f,20.5f,9.5f)
                line(8f,2.5f,8f,6f)
                line(16f,2.5f,16f,6f)
                line(8f,14f,12f,14f)
                line(8f,17f,15f,17f)
            }
            "AI" -> {
                drawRoundRect(tint,Offset(3*s,4*s),Size(18*s,13*s),CornerRadius(3f*s),style=stroke)
                path(8f,17f,6f,20.5f,11.5f,17f)
                line(8f,9f,16f,9f)
                line(8f,12.5f,13.5f,12.5f)
            }
            "Settings" -> {
                line(4f,7f,20f,7f)
                drawCircle(tint,2.2f*s,center=Offset(9*s,7*s),style=stroke)
                line(4f,12f,20f,12f)
                drawCircle(tint,2.2f*s,center=Offset(15*s,12*s),style=stroke)
                line(4f,17f,20f,17f)
                drawCircle(tint,2.2f*s,center=Offset(10*s,17*s),style=stroke)
            }
            "Send" -> {path(4f,5f,20f,12f,4f,19f,7f,12f,4f,5f);line(7f,12f,20f,12f)}
            "Voice" -> {drawRoundRect(tint,Offset(9*s,3*s),Size(6*s,11*s),CornerRadius(3f*s),style=stroke);path(5.5f,10.5f,5.5f,13.5f,8.5f,17.5f,15.5f,17.5f,18.5f,13.5f,18.5f,10.5f);line(12f,17.5f,12f,21f)}
            "Camera" -> {drawRoundRect(tint,Offset(3*s,6.5f*s),Size(18*s,13.5f*s),CornerRadius(2.5f*s),style=stroke);path(8f,6.5f,9.5f,4f,14.5f,4f,16f,6.5f);drawCircle(tint,3.5f*s,center=Offset(12*s,13.2f*s),style=stroke)}
            "Plus" -> {line(12f,5.5f,12f,18.5f);line(5.5f,12f,18.5f,12f)}
            "Arrow" -> {line(5f,12f,19f,12f);path(13.5f,6.5f,19f,12f,13.5f,17.5f)}
            "Check" -> path(4.5f,12f,9.5f,17f,19.5f,7f)
            "Appearance" -> {
                drawCircle(tint,7.5f*s,center=Offset(12*s,12*s),style=stroke)
                line(12f,4.5f,12f,19.5f)
                drawArc(tint,-90f,180f,true,Offset(4.5f*s,4.5f*s),Size(15f*s,15f*s))
            }
            "Petot" -> {
                line(12f,2.5f,12f,5.5f)
                drawCircle(tint,1.4f*s,center=Offset(12*s,2.5f*s))
                drawRoundRect(tint,Offset(4*s,5.5f*s),Size(16*s,14.5f*s),CornerRadius(4.5f*s),style=stroke)
                drawRoundRect(tint,Offset(6.5f*s,9f*s),Size(11*s,6.5f*s),CornerRadius(2.2f*s),style=stroke)
                drawCircle(tint,1.2f*s,center=Offset(9.5f*s,12.2f*s))
                drawCircle(tint,1.2f*s,center=Offset(14.5f*s,12.2f*s))
                drawArc(tint,20f,140f,false,Offset(10*s,14.8f*s),Size(4*s,2.5f*s),style=stroke)
            }
            "Reminders" -> {
                drawArc(tint,180f,180f,false,Offset(10.5f*s,2.5f*s),Size(3f*s,3f*s),style=stroke)
                val p=Path();p.moveTo(12*s,4.5f*s)
                p.cubicTo(8.5f*s,4.5f*s,6.5f*s,8.5f*s,6.5f*s,13f*s)
                p.lineTo(4.5f*s,16.5f*s);p.lineTo(19.5f*s,16.5f*s);p.lineTo(17.5f*s,13f*s)
                p.cubicTo(17.5f*s,8.5f*s,15.5f*s,4.5f*s,12*s,4.5f*s)
                drawPath(p,tint,style=stroke)
                line(4f,16.5f,20f,16.5f)
                drawArc(tint,0f,180f,false,Offset(10f*s,17f*s),Size(4*s,3.5f*s),style=stroke)
            }
            "Receipt" -> {
                drawRoundRect(tint,Offset(5*s,2.5f*s),Size(14*s,19f*s),CornerRadius(2.5f*s),style=stroke)
                line(8f,7f,16f,7f);line(8f,11f,16f,11f);line(8f,15f,13f,15f);line(14.5f,15f,16f,15f)
            }
            "Gemini" -> {
                val p=Path();p.moveTo(12*s,2.5f*s)
                p.quadraticTo(12*s,12*s,21.5f*s,12*s)
                p.quadraticTo(12*s,12*s,12*s,21.5f*s)
                p.quadraticTo(12*s,12*s,2.5f*s,12*s)
                p.quadraticTo(12*s,12*s,12*s,2.5f*s)
                drawPath(p,tint,style=stroke)
                val p2=Path();p2.moveTo(19*s,2.5f*s)
                p2.quadraticTo(19*s,5.5f*s,22*s,5.5f*s)
                p2.quadraticTo(19*s,5.5f*s,19*s,8.5f*s)
                p2.quadraticTo(19*s,5.5f*s,16*s,5.5f*s)
                p2.quadraticTo(19*s,5.5f*s,19*s,2.5f*s)
                drawPath(p2,tint,style=stroke)
            }
            "Security", "Privacy" -> {
                drawArc(tint,180f,180f,false,Offset(7.5f*s,3.5f*s),Size(9f*s,8f*s),style=stroke)
                line(7.5f,7.5f,7.5f,10.5f);line(16.5f,7.5f,16.5f,10.5f)
                drawRoundRect(tint,Offset(4.5f*s,10.5f*s),Size(15*s,10.5f*s),CornerRadius(2.5f*s),style=stroke)
                drawCircle(tint,1.3f*s,center=Offset(12*s,14.8f*s))
                line(12f,15.5f,12f,18f)
            }
            "Sync", "Cloud" -> {
                val p=Path();p.moveTo(7.5f*s,18f*s);p.lineTo(16.5f*s,18f*s)
                p.cubicTo(19f*s,18f*s,21f*s,16f*s,21f*s,13.5f*s)
                p.cubicTo(21f*s,11.2f*s,19.5f*s,9.3f*s,17.2f*s,9.1f*s)
                p.cubicTo(16.8f*s,6f*s,14.2f*s,3.5f*s,11f*s,3.5f*s)
                p.cubicTo(8.2f*s,3.5f*s,5.8f*s,5.5f*s,5.2f*s,8.2f*s)
                p.cubicTo(3.3f*s,8.8f*s,2f*s,10.6f*s,2f*s,12.8f*s)
                p.cubicTo(2f*s,15.6f*s,4.3f*s,18f*s,7.5f*s,18f*s)
                drawPath(p,tint,style=stroke)
                line(12f,9.5f,12f,14.5f);path(9.5f,12f,12f,9.5f,14.5f,12f)
            }
            "Cash" -> {
                drawRoundRect(tint,Offset(3*s,6*s),Size(18*s,12*s),CornerRadius(2.5f*s),style=stroke)
                drawCircle(tint,2.5f*s,center=Offset(12*s,12*s),style=stroke)
                line(5.5f,9f,5.5f,10.5f)
                line(18.5f,13.5f,18.5f,15f)
            }
            "Wallet" -> {
                drawRoundRect(tint,Offset(3*s,5*s),Size(18*s,14*s),CornerRadius(3f*s),style=stroke)
                line(3f,9.5f,21f,9.5f)
                drawRoundRect(tint,Offset(14*s,11*s),Size(7*s,5*s),CornerRadius(2f*s),style=stroke)
                drawCircle(tint,0.9f*s,center=Offset(16.5f*s,13.5f*s))
            }
            "Bank" -> {
                path(3f,8.5f,12f,3.5f,21f,8.5f)
                line(3f,8.5f,21f,8.5f)
                line(5.5f,9.5f,5.5f,16.5f)
                line(9.8f,9.5f,9.8f,16.5f)
                line(14.2f,9.5f,14.2f,16.5f)
                line(18.5f,9.5f,18.5f,16.5f)
                line(2.5f,17f,21.5f,17f)
                line(3.5f,19.5f,20.5f,19.5f)
            }
            "Savings" -> {
                val p=Path();p.moveTo(12*s,3.5f*s)
                p.lineTo(19.5f*s,6f*s)
                p.cubicTo(19.5f*s,13.5f*s,16f*s,18.5f*s,12*s,20.5f*s)
                p.cubicTo(8f*s,18.5f*s,4.5f*s,13.5f*s,4.5f*s,6f*s)
                p.close()
                drawPath(p,tint,style=stroke)
                path(9f,12f,11.2f,14.5f,15.5f,9.5f)
            }
            "Edit" -> {
                val p=Path()
                p.moveTo(15.5f*s,4.5f*s)
                p.lineTo(19.5f*s,8.5f*s)
                p.lineTo(8.5f*s,19.5f*s)
                p.lineTo(4.5f*s,19.5f*s)
                p.lineTo(4.5f*s,15.5f*s)
                p.close()
                drawPath(p,tint,style=stroke)
                line(13.5f,6.5f,17.5f,10.5f)
            }
            "Trash", "Delete" -> {
                line(4f,6f,20f,6f)
                path(9f,6f,9f,3.5f,15f,3.5f,15f,6f)
                path(6f,6f,7.2f,20f,16.8f,20f,18f,6f)
                line(10f,9.5f,10f,16.5f)
                line(14f,9.5f,14f,16.5f)
            }
            "Archive" -> {
                drawRoundRect(tint,Offset(3*s,4*s),Size(18*s,5*s),CornerRadius(1.5f*s),style=stroke)
                drawRoundRect(tint,Offset(4.5f*s,9*s),Size(15*s,11*s),CornerRadius(1.5f*s),style=stroke)
                line(10f,13f,14f,13f)
            }
            "Close", "Clear" -> {
                line(6f,6f,18f,18f)
                line(18f,6f,6f,18f)
            }
            "ChevronLeft", "Back" -> path(15f,5.5f,8.5f,12f,15f,18.5f)
            "ChevronRight", "Forward" -> path(9f,5.5f,15.5f,12f,9f,18.5f)
            "ChevronUp" -> path(5.5f,14.5f,12f,8f,18.5f,14.5f)
            "ChevronDown" -> path(5.5f,9.5f,12f,16f,18.5f,9.5f)
            "Refresh", "Undo" -> {
                drawArc(tint,45f,275f,false,Offset(4f*s,4f*s),Size(16f*s,16f*s),style=stroke)
                path(17.5f,6.5f,20f,11f,15.5f,11.5f)
            }
            "History" -> {
                drawCircle(tint,7.5f*s,center=Offset(12*s,12*s),style=stroke)
                line(12f,7.5f,12f,12f)
                line(12f,12f,16f,12f)
            }
            "Calculator" -> {
                drawRoundRect(tint,Offset(5f*s,3f*s),Size(14f*s,18f*s),CornerRadius(2.5f*s),style=stroke)
                line(8f,7.5f,16f,7.5f)
                line(8f,11.5f,10.5f,11.5f);line(13.5f,11.5f,16f,11.5f)
                line(8f,14.5f,10.5f,14.5f);line(13.5f,14.5f,16f,14.5f)
                line(8f,17.5f,16f,17.5f)
            }
            "Calendar", "Today" -> {
                drawRoundRect(tint,Offset(3.5f*s,4.5f*s),Size(17f*s,16f*s),CornerRadius(2.5f*s),style=stroke)
                line(3.5f,9.5f,20.5f,9.5f)
                line(8f,2.5f,8f,6f);line(16f,2.5f,16f,6f)
                drawCircle(tint,1.3f*s,center=Offset(12*s,14.5f*s))
            }
            "Search" -> {
                drawCircle(tint,5f*s,center=Offset(10.5f*s,10.5f*s),style=stroke)
                line(14.5f,14.5f,20f,20f)
            }
            "Filter" -> path(4f,5f,20f,5f,14f,12.5f,14f,18.5f,10f,19.5f,10f,12.5f,4f,5f)
            "Info" -> {
                drawCircle(tint,8f*s,center=Offset(12*s,12*s),style=stroke)
                line(12f,11f,12f,16.5f)
                drawCircle(tint,1.2f*s,center=Offset(12*s,8f*s))
            }
            "Share" -> {
                line(12f,13f,12f,3.5f);path(8.5f,7f,12f,3.5f,15.5f,7f)
                path(5f,11f,5f,20f,19f,20f,19f,11f)
            }
            "Download", "Import" -> {
                line(12f,3.5f,12f,13f);path(8.5f,9.5f,12f,13f,15.5f,9.5f)
                path(5f,13f,5f,20f,19f,20f,19f,13f)
            }
            else -> {path(6.5f,12.5f,10f,16f,17.5f,8.5f)}
        }
    }
}

@Composable internal fun HeroCard(modifier:Modifier=Modifier,startColor:Color=Color.Unspecified,accent:Color=Color.Unspecified,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}

@Composable internal fun SegmentedToggle(
    options:List<String>,
    selectedIndex:Int,
    onSelect:(Int)->Unit,
    modifier:Modifier=Modifier
) {
    Surface(
        modifier=modifier.fillMaxWidth(),
        shape=MaterialTheme.shapes.large,
        color=MaterialTheme.colorScheme.surfaceContainerLow,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed {index,label->
                val selected=index==selectedIndex
                Surface(
                    onClick={onSelect(index)},
                    modifier=Modifier.weight(1f).heightIn(min=FinanceDesign.TouchTarget),
                    shape=RoundedCornerShape(9.dp),
                    color=if(selected)MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shadowElevation=0.dp
                ) {
                    Box(Modifier.padding(horizontal=12.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
                        Text(
                            text=label,
                            style=MaterialTheme.typography.labelLarge,
                            fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Medium,
                            color=if(selected)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable internal fun ScreenHeading(title:String,subtitle:String,eyebrow:String?=null) {
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        if(eyebrow=="CASHA") {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(bottom=2.dp)) {
                androidx.compose.foundation.Image(painter=androidx.compose.ui.res.painterResource(com.example.casha.R.drawable.casha_logo),contentDescription="Casha logo",modifier=Modifier.size(24.dp))
                Text("Casha",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
            }
        } else if(eyebrow!=null) {
            Text(eyebrow.uppercase(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        }
        Text(title,style=MaterialTheme.typography.headlineMedium)
        Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun SectionLabel(title:String,subtitle:String?=null) {
    Column(Modifier.padding(top=4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        subtitle?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable internal fun NoticeCard(title:String,detail:String,action:String?=null,onAction:()->Unit={}) {
    Surface(
        color=MaterialTheme.colorScheme.surface,
        shape=MaterialTheme.shapes.large,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
        modifier=Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall)
            Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(action!=null)TextButton(onAction,contentPadding=PaddingValues(0.dp),shape=MaterialTheme.shapes.small){Text(action);Spacer(Modifier.width(6.dp));PilotIcon("Arrow",Modifier.size(15.dp))}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DateEntryField(value:String,onValueChange:(String)->Unit,label:String,enabled:Boolean=true) {
    var picking by androidx.compose.runtime.remember {androidx.compose.runtime.mutableStateOf(false)}
    OutlinedTextField(
        value=value,
        onValueChange=onValueChange,
        modifier=Modifier.fillMaxWidth(),
        label={Text(label)},
        singleLine=true,
        enabled=enabled,
        shape=MaterialTheme.shapes.large,
        trailingIcon={IconButton({picking=true},enabled=enabled,modifier=Modifier.semantics {contentDescription="Choose $label"}){PilotIcon("Calendar",Modifier.size(18.dp))}}
    )
    if(picking) {
        val initial=runCatching {java.time.LocalDate.parse(value).also {require(it.year in 1900..2200)}}.getOrDefault(java.time.LocalDate.now())
        val picker=rememberDatePickerState(initialSelectedDateMillis=initial.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),yearRange=1900..2200)
        DatePickerDialog(onDismissRequest={picking=false},confirmButton={TextButton({picker.selectedDateMillis?.let {onValueChange(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString())};picking=false},enabled=picker.selectedDateMillis!=null,shape=MaterialTheme.shapes.small){Text("Use date")}},dismissButton={TextButton({picking=false},shape=MaterialTheme.shapes.small){Text("Cancel")}}) {DatePicker(picker)}
    }
}

@Composable internal fun MoneyLine(label:String,amount:Long) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth=maxWidth
        val stacked=maxWidth<260.dp||androidx.compose.ui.platform.LocalDensity.current.fontScale>1.25f
        if(stacked)Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(money(amount),style=MaterialTheme.typography.titleSmall)}
        else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(money(amount),Modifier.widthIn(max=availableWidth*0.6f),style=MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable internal fun SettingsGroup(content:@Composable ColumnScope.()->Unit) {
    Surface(
        modifier=Modifier.fillMaxWidth(),
        shape=MaterialTheme.shapes.large,
        color=MaterialTheme.colorScheme.surface,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}

@Composable internal fun PreferenceToggle(title:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=FinanceDesign.TouchTarget).toggleable(value=checked,role=Role.Switch,onValueChange=onChange),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Switch(checked,onCheckedChange=null)
    }
}

@Composable internal fun ConsentToggle(title:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=FinanceDesign.TouchTarget).toggleable(value=checked,role=Role.Checkbox,onValueChange=onChange),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Checkbox(checked,onCheckedChange=null)
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
    }
}

/** A single neutral dialog layout shared by financial forms and confirmations. */
@Composable internal fun PilotModal(
    onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,
    confirmButton:@Composable ()->Unit,dismissButton:(@Composable ()->Unit)?=null,
    category:String="CASHA",icon:String="Loans",accent:Color=Color.Unspecified
) {
    Dialog(onDismissRequest,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),contentAlignment=Alignment.Center) {
            Surface(Modifier.widthIn(max=540.dp).fillMaxWidth().heightIn(max=maxHeight),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.heightIn(max=maxHeight)) {
                    Box(Modifier.fillMaxWidth().padding(20.dp)) {ProvideTextStyle(MaterialTheme.typography.titleLarge,content=title)}
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f,fill=false).fillMaxWidth().padding(20.dp)) {ProvideTextStyle(MaterialTheme.typography.bodyMedium,content=text)}
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    FlowRow(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.End,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        dismissButton?.let {it();Spacer(Modifier.width(8.dp))};confirmButton()
                    }
                }
            }
        }
    }
}
@Composable internal fun DetailDialog(onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,dismissButton:@Composable ()->Unit,accent:Color=Color.Unspecified,eyebrow:String="Loan details") {
    Dialog(onDismissRequest,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),contentAlignment=Alignment.Center) {
            Surface(Modifier.widthIn(max=600.dp).fillMaxWidth().heightIn(max=maxHeight*0.92f),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.heightIn(max=maxHeight*0.92f)) {
                    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(eyebrow,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);ProvideTextStyle(MaterialTheme.typography.titleLarge,content=title)}
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f,fill=false).fillMaxWidth().padding(horizontal=20.dp,vertical=16.dp)){text()}
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    FlowRow(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.End,verticalArrangement=Arrangement.spacedBy(8.dp)) {dismissButton();Spacer(Modifier.width(8.dp));confirmButton()}
                }
            }
        }
    }
}

@Composable internal fun SectionRegion(title:String,subtitle:String?=null,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SectionLabel(title,subtitle)
        content()
    }
}
@Composable internal fun Disclosure(title:String,expanded:Boolean,onToggle:()->Unit,modifier:Modifier=Modifier) {
    TextButton(onToggle,modifier=modifier.fillMaxWidth().heightIn(min=FinanceDesign.TouchTarget),contentPadding=PaddingValues(horizontal=4.dp,vertical=8.dp),shape=MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurface)
            PilotIcon(if(expanded)"ChevronUp" else "ChevronDown",Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable internal fun FinancialListRow(title:String,subtitle:String,amount:Long,status:String?=null,statusColor:Color=MaterialTheme.colorScheme.onSurfaceVariant,onClick:(()->Unit)?=null) {
    Surface(color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(onClick=onClick) else Modifier),shape=RoundedCornerShape(0.dp)) {
        Column(Modifier.padding(vertical=12.dp,horizontal=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
                Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                Text(money(amount),Modifier.widthIn(max=180.dp),style=MaterialTheme.typography.titleSmall)
            }
            Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            status?.let {Text(it,style=MaterialTheme.typography.labelMedium,color=statusColor)}
        }
    }
}