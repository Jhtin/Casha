package com.example.casha.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.sin
import kotlin.math.abs
import com.example.casha.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.casha.domain.*
import java.time.LocalDate
import kotlinx.coroutines.delay

enum class PetotMood(val label:String,val caption:String) {
    HELLO("Hello","happy to see you"), CHEER("Happy","cheering you on"), CARE("Gentle care","gentle check-in"),
    THINKING("Thinking","thinking it through"), PROUD("Proud","proud of your progress"), LOVE("Love","a little encouragement"),
    SURPRISED("Surprised","a little surprise"), SLEEPY("Sleepy","taking it gently"), RELIEVED("Relieved","a little breathing room")
}
data class PetotReaction(val title:String,val message:String,val mood:PetotMood,val eventId:String="")

/** Reactions describe confirmed ledger changes; they never alter a budget or initiate AI requests. */
internal fun companionReaction(oldLoans:List<Loan>,loans:List<Loan>,oldCash:List<CashEntry>,cash:List<CashEntry>,profile:PaydayProfile,policies:Map<String,DebtPolicy>,configured:Boolean,today:LocalDate=LocalDate.now()):PetotReaction? {
    val newCash=cash.filter {entry->oldCash.none {it.id==entry.id}&&!entry.id.startsWith("undo_")&&!entry.isWalletTransfer}.lastOrNull()
    val payment=loans.flatMap {loan->
        val old=oldLoans.find {it.id==loan.id}
        if(old==null)emptyList() else loan.payments.filter {p->old.payments.none {it.id==p.id}&&loan.reversals.none {it.paymentId==p.id}}.map {loan to it}
    }.lastOrNull()
    if(newCash==null&&payment==null)return null
    val currentBudget=configured&&!today.isBefore(profile.start)&&today.isBefore(profile.next)
    val before=PaydayPlanner.calculate(profile,oldLoans,policies,oldCash,today)
    val after=PaydayPlanner.calculate(profile,loans,policies,cash,today)
    if(newCash!=null) {
        if(newCash.income)return PetotReaction("A little boost!","Income recorded. Every bit helps your plan grow.",PetotMood.PROUD)
        val inCutoff=!newCash.date.isBefore(profile.start)&&newCash.date.isBefore(profile.next)
        if(currentBudget&&inCutoff&&after.shortfall>before.shortfall)return PetotReaction("Let's give your budget a breather","Expense saved. Your planned needs and payments are now short by ${money(after.shortfall)}. Check Payday before your next spend.",PetotMood.CARE)
        if(currentBudget&&inCutoff&&newCash.amount>before.daily)return PetotReaction("Tiny pause, big care","Expense saved. ${money(newCash.amount)} is above your ${money(before.daily)} daily allowance before this entry. Check what's left in Payday.",PetotMood.CARE)
        return PetotReaction("Logged it, little legend!","Expense saved. Keeping track is a win, too.",PetotMood.CHEER)
    }
    val (loan,p)=payment!!
    if(currentBudget&&after.cash<0&&after.cash<before.cash)return PetotReaction("Payment saved — let's check your cash","Your budget cash is now ${money(after.cash)}. Review Payday and protect your next essentials.",PetotMood.CARE)
    if(loan.remaining==0L)return PetotReaction("One loan down!","Paid in full! Your record is safe in Show archived. Take a little victory moment.",PetotMood.CHEER)
    return PetotReaction(if(loan.mode==LoanMode.I_OWE)"A little closer to freedom!" else "Payment received!","${money(p.amount)} recorded. Nice progress, one step at a time.",PetotMood.PROUD)
}

internal object CompanionPreference {
    fun prefs(context:Context)=context.applicationContext.getSharedPreferences("app_companion",Context.MODE_PRIVATE)
    fun read(context:Context)=prefs(context).let {it.getBoolean("reactions",true) to it.getBoolean("motion",true)}
    fun save(context:Context,key:String,value:Boolean)=prefs(context).edit().putBoolean(key,value).commit()
}
@Composable internal fun rememberCompanionOptions():State<Pair<Boolean,Boolean>> {
    val context=LocalContext.current.applicationContext
    val value=remember(context){mutableStateOf(CompanionPreference.read(context))}
    DisposableEffect(context) {
        val prefs=CompanionPreference.prefs(context)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,_->value.value=CompanionPreference.read(context)}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        value.value=CompanionPreference.read(context)
        onDispose {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return value
}

@Composable internal fun PetotCharacter(mood:PetotMood,motion:Boolean,modifier:Modifier=Modifier) {
    val useWaveSheet = mood==PetotMood.HELLO || mood==PetotMood.CHEER
    val sheet = ImageBitmap.imageResource(if(useWaveSheet) R.drawable.petot_sprites else R.drawable.petot_emotions)
    val phase = remember { Animatable(0f) }
    val density = LocalDensity.current.density
    LaunchedEffect(mood,motion) {
        phase.snapTo(0f)
        if(motion && ValueAnimator.areAnimatorsEnabled()) {
            phase.animateTo(1f, tween(durationMillis=4000, easing=LinearEasing))
            phase.snapTo(0f)
        }
    }
    val p = phase.value
    val wave = sin(p * Math.PI * when(mood) { PetotMood.SLEEPY,PetotMood.RELIEVED -> 2; PetotMood.THINKING,PetotMood.CARE -> 4; else -> 12 }).toFloat()
    val turns = when(mood) {
        PetotMood.SURPRISED -> wave*7f
        PetotMood.SLEEPY -> wave*5f
        PetotMood.THINKING -> wave*3.5f
        PetotMood.CARE,PetotMood.RELIEVED -> wave*1.5f
        else -> wave*4f
    }
    val lift = when(mood) { PetotMood.PROUD,PetotMood.CHEER -> 7f;PetotMood.LOVE -> 4f;PetotMood.SLEEPY,PetotMood.CARE -> 1.5f;else -> 3f }
    val stretch = when(mood) {PetotMood.SURPRISED -> .05f;PetotMood.RELIEVED -> .04f;else -> .02f}
    Canvas(modifier.size(88.dp).graphicsLayer {
        translationY = -abs(wave)*lift*density
        rotationZ = turns
        scaleX = 1f + abs(wave)*stretch
        scaleY = 1f - abs(wave)*stretch*.5f
        transformOrigin = TransformOrigin(.5f,.9f)
    }.semantics { contentDescription="Petot, your little money companion";stateDescription=mood.label }) {
        val columns = if(useWaveSheet)2 else 3
        val frame = if(useWaveSheet) {
            val sequence=if(mood==PetotMood.CHEER)listOf(0,1,0,1,2,0,3,3) else listOf(0,1,0,1,0,2,0,0)
            if(mood==PetotMood.CHEER && p==0f) 3 else sequence[(p*16).toInt().mod(sequence.size)]
        } else when(mood) {
            PetotMood.CARE->2;PetotMood.THINKING->3;PetotMood.PROUD->4;PetotMood.LOVE->5
            PetotMood.SURPRISED->6;PetotMood.SLEEPY->7;PetotMood.RELIEVED->8;else->0
        }
        val width=sheet.width/columns;val height=sheet.height/columns
        drawImage(sheet,srcOffset=IntOffset((frame%columns)*width,(frame/columns)*height),
            srcSize=IntSize(width,height),dstSize=IntSize(size.width.toInt(),size.height.toInt()))
        // Finite, lightweight accents stay inside the character bounds and respect the motion switch.
        if(p>0f && mood==PetotMood.THINKING) repeat(3) {i->
            val alpha=(.35f+.65f*abs(sin((p*3+i*.25)*Math.PI).toFloat())).coerceIn(0f,1f)
            drawCircle(Color(0xFFF6BA49).copy(alpha=alpha),size.width*.018f,Offset(size.width*(.12f+i*.07f),size.height*.08f))
        }
        if(p>0f && mood==PetotMood.PROUD) repeat(2) {i->
            val x=size.width*(if(i==0).1f else .9f);val y=size.height*(.18f-abs(wave)*.05f)
            drawLine(Color(0xFFF6BA49),Offset(x-3.dp.toPx(),y),Offset(x+3.dp.toPx(),y),1.5.dp.toPx())
            drawLine(Color(0xFFF6BA49),Offset(x,y-3.dp.toPx()),Offset(x,y+3.dp.toPx()),1.5.dp.toPx())
        }
    }
}

@Composable internal fun PetotPopup(reaction:PetotReaction,motion:Boolean,dismiss:()->Unit) {
    val context=LocalContext.current
    PetotSound(reaction)
    val dismissCurrent by rememberUpdatedState(dismiss)
    LaunchedEffect(reaction) {
        val accessibility=context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if(accessibility?.isTouchExplorationEnabled!=true){delay(5000L);dismissCurrent()}
    }
    Popup(alignment=Alignment.TopCenter,onDismissRequest={},properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false)) {
        Surface(Modifier.statusBarsPadding().padding(12.dp).widthIn(max=480.dp).fillMaxWidth().semantics {liveRegion=LiveRegionMode.Polite}.testTag("petot-popup"),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh,shadowElevation=6.dp,border=androidx.compose.foundation.BorderStroke(1.dp,if(reaction.mood==PetotMood.CARE)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(start=12.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                key(reaction.eventId) { PetotCharacter(reaction.mood,motion) }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Petot · ${reaction.mood.caption}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(reaction.title,style=MaterialTheme.typography.titleSmall)
                    Text(reaction.message,style=MaterialTheme.typography.bodySmall)
                }
                IconButton(dismiss,Modifier.semantics {contentDescription="Dismiss Petot message"}) {Text("×",style=MaterialTheme.typography.titleLarge)}
            }
        }
    }
}
