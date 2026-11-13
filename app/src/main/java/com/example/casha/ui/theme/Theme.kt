package com.example.casha.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Shared spacing, shape and palette tokens for the financial interface. */
object FinanceDesign {
    val PagePadding=20.dp
    val SectionGap=24.dp
    val ContentGap=12.dp
    val Radius=10.dp
    val TouchTarget=48.dp
    val Blue=Color(0xFF2563EB)
    val Success=Color(0xFF15803D)
    val Warning=Color(0xFFB45309)
    val Danger=Color(0xFFB91C1C)
}
private val Light=lightColorScheme(
    primary=FinanceDesign.Blue,onPrimary=Color.White,primaryContainer=Color(0xFFEFF6FF),onPrimaryContainer=Color(0xFF1D4ED8),
    secondary=FinanceDesign.Success,onSecondary=Color.White,secondaryContainer=Color(0xFFEEF7F0),onSecondaryContainer=Color(0xFF166534),
    tertiary=FinanceDesign.Warning,onTertiary=Color.White,tertiaryContainer=Color(0xFFFFF7ED),onTertiaryContainer=Color(0xFF92400E),
    background=Color(0xFFF8FAFC),onBackground=Color(0xFF0F172A),surface=Color.White,onSurface=Color(0xFF0F172A),
    onSurfaceVariant=Color(0xFF64748B),surfaceVariant=Color(0xFFF1F5F9),surfaceContainerLowest=Color.White,
    surfaceContainerLow=Color(0xFFF8FAFC),surfaceContainer=Color(0xFFF1F5F9),surfaceContainerHigh=Color(0xFFF1F5F9),surfaceContainerHighest=Color(0xFFE8EEF5),
    outline=Color(0xFF64748B),outlineVariant=Color(0xFFE2E8F0),error=FinanceDesign.Danger,onError=Color.White,errorContainer=Color(0xFFFEF2F2),onErrorContainer=Color(0xFF991B1B)
)
private val Dark=darkColorScheme(
    primary=Color(0xFF93B8FF),onPrimary=Color(0xFF102C5C),primaryContainer=Color(0xFF172B49),onPrimaryContainer=Color(0xFFBFDBFE),
    secondary=Color(0xFF86CFA1),onSecondary=Color(0xFF103820),secondaryContainer=Color(0xFF172D23),onSecondaryContainer=Color(0xFFBBE8CA),
    tertiary=Color(0xFFE8BD7A),onTertiary=Color(0xFF402700),tertiaryContainer=Color(0xFF30271C),onTertiaryContainer=Color(0xFFF3D5A1),
    background=Color(0xFF0B1220),onBackground=Color(0xFFE2E8F0),surface=Color(0xFF111C2E),onSurface=Color(0xFFE2E8F0),
    onSurfaceVariant=Color(0xFFA7B4C7),surfaceVariant=Color(0xFF1A2739),surfaceContainerLowest=Color(0xFF0B1220),
    surfaceContainerLow=Color(0xFF111C2E),surfaceContainer=Color(0xFF172338),surfaceContainerHigh=Color(0xFF1A2739),surfaceContainerHighest=Color(0xFF233247),
    outline=Color(0xFF8091AA),outlineVariant=Color(0xFF334155),error=Color(0xFFF2A5A5),onError=Color(0xFF4A1212),errorContainer=Color(0xFF311D24),onErrorContainer=Color(0xFFFECACA)
)
@Composable fun CashaTheme(darkTheme:Boolean?=null,dynamicColor:Boolean=false,content:@Composable ()->Unit) {
    val mode=rememberThemeMode().value
    val useDark=darkTheme?:when(mode){ThemeMode.SYSTEM->isSystemInDarkTheme();ThemeMode.LIGHT->false;ThemeMode.DARK->true}
    val colors=if(dynamicColor&&Build.VERSION.SDK_INT>=31) {if(useDark)dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)} else if(useDark)Dark else Light
    val view=LocalView.current
    val context=LocalContext.current
    SideEffect {
        var host:android.content.Context?=context
        while(host is android.content.ContextWrapper && host !is android.app.Activity)host=host.baseContext
        (host as? android.app.Activity)?.window?.let {window->
            androidx.core.view.WindowCompat.getInsetsController(window,view).apply {isAppearanceLightStatusBars=!useDark;isAppearanceLightNavigationBars=!useDark}
        }
    }
    MaterialTheme(colorScheme=colors,typography=Typography,shapes=Shapes(extraSmall=RoundedCornerShape(4.dp),small=RoundedCornerShape(8.dp),medium=RoundedCornerShape(10.dp),large=RoundedCornerShape(12.dp),extraLarge=RoundedCornerShape(12.dp)),content=content)
}
