package com.example.casha.reminders

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.casha.MainActivity
import com.example.casha.data.*
import com.example.casha.domain.ReminderPolicy
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.*
import java.util.concurrent.TimeUnit

object BackgroundWork {
    fun initialize(context:Context) {
        val work=WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork("casha-reminders",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<ReminderWorker>(1,TimeUnit.HOURS).build())
        work.enqueueUniquePeriodicWork("casha-cloud-repair",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<CloudSyncWorker>(6,TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        scheduleMorning(context)
    }
    fun requestSync(context:Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("casha-cloud-upload",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CloudSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
    }
    fun checkReminders(context:Context) { WorkManager.getInstance(context).enqueueUniqueWork("casha-reminder-check",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<ReminderWorker>().build()) }
    fun scheduleMorning(context:Context) {
        val store=FinanceStore.current(context);val alarm=context.getSystemService(AlarmManager::class.java)
        val pending=PendingIntent.getBroadcast(context,77,Intent(context,ReminderReceiver::class.java).setAction("REMINDER_CHECK"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if(!store.flag("reminders"))return
        val now=ZonedDateTime.now();var next=now.withHour(store.preferences.getInt("reminderHour",9).coerceIn(0,23)).withMinute(0).withSecond(0).withNano(0)
        if(!next.isAfter(now))next=next.plusDays(1)
        // Inexact scheduling avoids a restricted exact-alarm permission and works with the hourly repair job.
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.toInstant().toEpochMilli(),pending)
    }
}
class CloudSyncWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val auth=FirebaseAuth.getInstance();val uid=auth.currentUser?.uid?:return Result.success()
        val repository=CloudRepositorySessions.acquire(uid) { CloudLoanRepository(applicationContext,uid,{auth.currentUser?.uid},FirestoreLoanCloud(uid,FirebaseFirestore.getInstance())) }
        return try {
            if(!repository.state.value.enabled) Result.success()
            else {
                repository.retry()
                val finished=withTimeoutOrNull(45_000) { combine(repository.state,repository.receivedServer) {state,fresh->state to fresh}
                    .first {(state,fresh)->(state.pending==0&&fresh)||state.conflictLoan!=null||state.message.startsWith("Firebase setup needed")||state.message.startsWith("Firebase denied")}}
                if(finished?.first?.pending==0&&finished.second&&auth.currentUser?.uid==uid) {
                    FinanceStore(applicationContext, FinanceStore.scopeFor(uid)).cacheLoans(repository.load())
                    BackgroundWork.checkReminders(applicationContext)
                }
                if(finished==null && runAttemptCount<5) Result.retry() else Result.success()
            }
        } finally { CloudRepositorySessions.release(uid,repository) }
    }
}
class ReminderWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
    override fun doWork():Result {
        val store=FinanceStore.current(applicationContext)
        if(!store.flag("reminders"))return Result.success()
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(applicationContext,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return Result.success()
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("loan_due","Loan reminders",NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility=Notification.VISIBILITY_PRIVATE })
        if(!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled())return Result.success()
        val today=LocalDate.now();val now=LocalTime.now()
        if(now.hour<store.preferences.getInt("reminderHour",9))return Result.success()
        val loans=runCatching { store.cachedLoans() }.getOrElse { return Result.failure() }
        loans.forEach { loan ->
            val id=loan.id.hashCode();val next=loan.installments().firstOrNull { it.remaining>0 }
            if(next==null||store.flag("archived_${loan.id}")) {manager.cancel(id);return@forEach}
            val key="${loan.id}_${next.index}"
            val stage=ReminderPolicy.stage(next.due,today,store.preferences.getInt("leadDays",3).coerceIn(0,7))?:return@forEach
            if(store.preferences.getLong("snooze_$key",0)>System.currentTimeMillis())return@forEach
            val sentFingerprint="$today|${next.remaining}|${next.due}"
            if(store.preferences.getString("sent_$key",null)==sentFingerprint)return@forEach
            val open=Intent(applicationContext,MainActivity::class.java).putExtra("openLoan",loan.id).putExtra("financeScope",store.scope)
                .setData(Uri.parse("casha://reminder/${store.scope}/${loan.id}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending=PendingIntent.getActivity(applicationContext,id,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val snooze=PendingIntent.getBroadcast(applicationContext,id,Intent(applicationContext,ReminderReceiver::class.java).setAction("SNOOZE")
                .setData(Uri.parse("casha://snooze/${store.scope}/$key")).putExtra("scope",store.scope).putExtra("key",key).putExtra("notification",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(applicationContext,"loan_due").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$stage · ${loan.party}").setContentText("PHP ${java.math.BigDecimal.valueOf(next.remaining,2)} · ${next.due}")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setPublicVersion(NotificationCompat.Builder(applicationContext,"loan_due").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Casha reminder").setContentText("Unlock to review").build())
                .addAction(0,if(loan.mode==com.example.casha.domain.LoanMode.I_OWE)"Mark as paid" else "Record received",pending).addAction(0,"Snooze 1 day",snooze).build()
            if (store.scope != FinanceStore.scopeFor(FirebaseAuth.getInstance().currentUser?.uid)) return Result.success()
            manager.notify(id,notification)
            store.preferences.edit().putString("sent_$key",sentFingerprint).commit()
        }
        BackgroundWork.scheduleMorning(applicationContext)
        return Result.success()
    }
}
class ReminderReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val store=FinanceStore.current(context)
        if(intent.action=="SNOOZE") {
            if(intent.getStringExtra("scope")!=store.scope)return
            val key=intent.getStringExtra("key")?:return
            store.preferences.edit().putLong("snooze_$key",System.currentTimeMillis()+86_400_000).commit()
            context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("notification",0))
        } else {BackgroundWork.checkReminders(context);BackgroundWork.scheduleMorning(context)}
    }
}
