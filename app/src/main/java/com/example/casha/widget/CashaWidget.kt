package com.example.casha.widget

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.example.casha.MainActivity
import com.example.casha.R
import com.example.casha.data.*
import com.google.firebase.auth.FirebaseAuth
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency

class CashaWidget:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray)=refreshAll(context)
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:Bundle)=refreshAll(context)
    override fun onReceive(context:Context,intent:Intent) {
        super.onReceive(context,intent)
        if(intent.action in setOf(REFRESH,Intent.ACTION_DATE_CHANGED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))refreshAll(context)
    }
    companion object {
        const val REFRESH="com.example.casha.widget.REFRESH"
        val actions=setOf("cash","expense","loans","payday")
        fun launchIntent(context:Context,scope:String,action:String)=Intent(context,MainActivity::class.java)
            .setData(Uri.parse("casha://widget/$scope/$action"))
            .putExtra("widgetAction",action).putExtra("widgetScope",scope)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        private fun pending(context:Context,scope:String,action:String)=PendingIntent.getActivity(context,0,launchIntent(context,scope,action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Synchronized fun refreshAll(context:Context) {
            val manager=AppWidgetManager.getInstance(context)
            val ids=manager.getAppWidgetIds(ComponentName(context,CashaWidget::class.java))
            if(ids.isEmpty())return
            val uid=FirebaseAuth.getInstance().currentUser?.uid
            val scope=FinanceStore.scopeFor(uid)
            val privacy=context.getSharedPreferences("app_privacy",Context.MODE_PRIVATE)
            val hidden=privacy.getBoolean("appLock",false)||privacy.getBoolean("hideWidgetDetails",false)
            val summary=if(hidden)null else runCatching {
                val loans=if(uid==null)LoanCodec.decode(context.getSharedPreferences("manual_loans",Context.MODE_PRIVATE).getString("loans","[]")!!)
                    else widgetJournalLoans(context.getSharedPreferences("cloud_loans_$scope",Context.MODE_PRIVATE).getString("journal",null)?:error("Account cache not loaded"))
                widgetSummary(loans)
            }.getOrNull()
            ids.forEach {id->
                val options=manager.getAppWidgetOptions(id)
                val compact=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,200)<220
                val views=views(context,scope,summary,hidden,compact)
                // Never publish a previous account's details after an identity change.
                if(FirebaseAuth.getInstance().currentUser?.uid==uid)manager.updateAppWidget(id,views)
            }
        }
        internal fun views(context:Context,scope:String,summary:WidgetSummary?,hidden:Boolean,compact:Boolean):RemoteViews {
            val views=RemoteViews(context.packageName,R.layout.casha_widget)
            views.setOnClickPendingIntent(R.id.widget_header,pending(context,scope,"loans"))
            views.setOnClickPendingIntent(R.id.widget_cash,pending(context,scope,"cash"))
            views.setOnClickPendingIntent(R.id.widget_expense,pending(context,scope,"expense"))
            views.setOnClickPendingIntent(R.id.widget_due,pending(context,scope,"loans"))
            views.setOnClickPendingIntent(R.id.widget_payday,pending(context,scope,"payday"))
            val refresh=PendingIntent.getBroadcast(context,0,Intent(context,CashaWidget::class.java).setAction(REFRESH),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_refresh,refresh)
            val next=summary?.next
            val line=when {
                hidden->"Loan details hidden"
                summary==null->"Open Casha to review saved records"
                next==null->"No outstanding loan payments"
                else->"${summary.overdue} overdue  ·  ${summary.thisWeek} due in 7 days"
            }
            views.setTextViewText(R.id.widget_summary,line)
            val today=LocalDate.now()
            val date=next?.date?.let {when {it<today->"Overdue";it==today->"Due today";it==today.plusDays(1)->"Due tomorrow";else->"Due ${it.format(DateTimeFormatter.ofPattern("MMM d"))}"}}
            val money=next?.amount?.let {NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("en-PH")).apply {currency=Currency.getInstance("PHP")}.format(BigDecimal.valueOf(it,2))}
            views.setTextViewText(R.id.widget_due,when {hidden->"Open the app to see your loans";next!=null->"$date · $money\n${next.party}";else->"Plan ahead in Payday"})
            views.setViewVisibility(R.id.widget_due,if(compact)View.GONE else View.VISIBLE)
            return views
        }
    }
}
