package com.example.casha

import android.app.Application
import android.app.NotificationManager
import com.example.casha.data.FinanceStore
import com.example.casha.reminders.BackgroundWork
import com.google.firebase.auth.FirebaseAuth

/** Identity cleanup also runs when the UI is locked or only background work is running. */
class CashaApplication : Application() {
    private var widgetLoans:android.content.SharedPreferences?=null
    private lateinit var widgetPrivacy:android.content.SharedPreferences
    private val widgetListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,_->runCatching {com.example.casha.widget.CashaWidget.refreshAll(this)}}
    override fun onCreate() {
        super.onCreate()
        widgetPrivacy=getSharedPreferences("app_privacy",MODE_PRIVATE)
        widgetPrivacy.registerOnSharedPreferenceChangeListener(widgetListener)
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val scope = FinanceStore.scopeFor(auth.currentUser?.uid)
            widgetLoans?.unregisterOnSharedPreferenceChangeListener(widgetListener)
            widgetLoans=getSharedPreferences(if(auth.currentUser==null)"manual_loans" else "cloud_loans_$scope",MODE_PRIVATE)
            widgetLoans?.registerOnSharedPreferenceChangeListener(widgetListener)
            runCatching {com.example.casha.widget.CashaWidget.refreshAll(this)}
            val privacy = getSharedPreferences("app_privacy", MODE_PRIVATE)
            if (privacy.getString("notificationScope", null) != scope) {
                getSystemService(NotificationManager::class.java)?.cancelAll()
                privacy.edit().putString("notificationScope", scope).commit()
                BackgroundWork.scheduleMorning(this)
            }
        }
    }
}
