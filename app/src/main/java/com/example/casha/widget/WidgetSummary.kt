package com.example.casha.widget

import com.example.casha.data.*
import com.example.casha.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal data class WidgetDue(val loanId:String,val party:String,val date:LocalDate,val amount:Long)
internal data class WidgetSummary(val overdue:Int,val thisWeek:Int,val next:WidgetDue?)
internal fun widgetSummary(loans:List<Loan>,today:LocalDate=LocalDate.now()):WidgetSummary {
    val dues=loans.filter {it.mode==LoanMode.I_OWE}.flatMap {loan->loan.installments().filter {it.remaining>0}.map {WidgetDue(loan.id,loan.party,it.due,it.remaining)}}
        .sortedWith(compareBy<WidgetDue> {it.date}.thenBy {it.loanId})
    return WidgetSummary(dues.count {it.date<today},dues.count {it.date>=today && it.date<=today.plusDays(6)},dues.firstOrNull())
}
/** Same local base + queue projection as the account repository, without opening a cloud session. */
internal fun widgetJournalLoans(raw:String):List<Loan> {
    val root=JSONObject(raw)
    val deleted=root.optJSONArray("deletedIds")?:JSONArray()
    val deletedIds=(0 until deleted.length()).map {deleted.getString(it)}.toSet()
    val loans=LoanCodec.decode(root.optString("base","[]")).filterNot {it.id in deletedIds}.associateBy {it.id}.toMutableMap()
    CloudQueueCodec.decode(root.optJSONArray("queue")?:JSONArray()).forEach {
        if(it.kind==MutationKind.DELETE)loans.remove(it.after.id) else loans[it.after.id]=it.after
    }
    return loans.values.toList()
}
