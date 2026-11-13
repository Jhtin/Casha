package com.example.casha.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable internal fun SettingsSection(
    title:String,
    summary:String,
    expanded:Boolean,
    onToggle:()->Unit,
    icon:String?=null,
    modifier:Modifier=Modifier,
    content:@Composable ColumnScope.()->Unit
) {
    val resolvedIcon = icon ?: when {
        title.contains("Appearance", ignoreCase = true) -> "Appearance"
        title.contains("Petot", ignoreCase = true) -> "Petot"
        title.contains("Reminder", ignoreCase = true) -> "Reminders"
        title.contains("Receipt", ignoreCase = true) -> "Receipt"
        title.contains("Gemini", ignoreCase = true) -> "Gemini"
        title.contains("Privacy", ignoreCase = true) || title.contains("Backup", ignoreCase = true) -> "Security"
        title.contains("Account", ignoreCase = true) || title.contains("Sync", ignoreCase = true) -> "Sync"
        else -> "Settings"
    }

    Surface(
        modifier.fillMaxWidth().animateContentSize(),
        shape=MaterialTheme.shapes.large,
        color=MaterialTheme.colorScheme.surface,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role=Role.Button,onClick=onToggle)
                    .semantics {stateDescription=if(expanded)"Expanded" else "Collapsed"}
                    .padding(horizontal=16.dp, vertical=16.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape=RoundedCornerShape(12.dp),
                    color=if(expanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier=Modifier.size(42.dp)
                ) {
                    Box(contentAlignment=Alignment.Center) {
                        PilotIcon(
                            name=resolvedIcon,
                            modifier=Modifier.size(20.dp),
                            tint=if(expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(title,style=MaterialTheme.typography.titleSmall)
                    Text(summary,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PilotIcon(
                    name = if (expanded) "ChevronUp" else "ChevronDown",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if(expanded)Column(Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
        }
    }
}
