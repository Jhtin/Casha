package com.example.casha.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.casha.widget.CashaWidget

@Composable internal fun HomeWidgetSettings() {
    val context=LocalContext.current
    val prefs=remember {context.getSharedPreferences("app_privacy",Context.MODE_PRIVATE)}
    var expanded by remember {mutableStateOf(false)}
    var hidden by remember {mutableStateOf(prefs.getBoolean("hideWidgetDetails",false))}
    var message by remember {mutableStateOf<String?>(null)}
    SettingsSection("Home screen widget","Cash shortcuts & upcoming dues",expanded,{expanded=!expanded},icon="Payday") {
        Text("Log cash, add expenses, and check your next loan payment from your home screen. Shortcuts open a form for you to review before saving.",style=MaterialTheme.typography.bodySmall)
        PreferenceToggle("Hide widget loan details",hidden,{value->
            if(prefs.edit().putBoolean("hideWidgetDetails",value).commit()) {hidden=value;CashaWidget.refreshAll(context)}
        })
        Text("App lock always hides loan details on the widget.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton({
            val manager=AppWidgetManager.getInstance(context)
            message=if(manager.isRequestPinAppWidgetSupported) {
                if(manager.requestPinAppWidget(ComponentName(context,CashaWidget::class.java),null,null))"Confirm Add on your home screen to place the widget." else "Long-press your home screen, choose Widgets, then Casha."
            } else "Long-press your home screen, choose Widgets, then Casha."
        },modifier=Modifier.fillMaxWidth()) {Text("Add Casha widget")}
        message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
    }
}
