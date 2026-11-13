package com.example.casha

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.widget.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class CashaWidgetTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun providerIsRegisteredAndSupportsResizing() {
        val info=AppWidgetManager.getInstance(context).installedProviders.single {it.provider==ComponentName(context,CashaWidget::class.java)}
        assertEquals(R.layout.casha_widget,info.initialLayout)
        assertTrue(info.minResizeWidth>0);assertEquals(1800000,info.updatePeriodMillis)
    }
    @Test fun normalCompactAndPrivateLayoutsInflateAndHaveEssentialShortcuts() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val due=WidgetDue("sample","Sample loan",LocalDate.now().minusDays(1),123456)
            val summary=WidgetSummary(1,2,due)
            val folder=File(context.filesDir,"phase_previews").apply {mkdirs()}
            for((name,hidden,compact) in listOf(Triple("widget",false,false),Triple("widget-private",true,false),Triple("widget-compact",false,true))) {
                val view=CashaWidget.views(context,"device",summary,hidden,compact).apply(context,null)
                assertEquals("＋ Log cash",view.findViewById<TextView>(R.id.widget_cash).text.toString())
                assertEquals("Add expense",view.findViewById<TextView>(R.id.widget_expense).contentDescription)
                val text=view.findViewById<TextView>(R.id.widget_due).text.toString()
                if(hidden){assertFalse(text.contains("Sample loan"));assertFalse(text.contains("1,234"));assertEquals("Loan details hidden",view.findViewById<TextView>(R.id.widget_summary).text.toString())}
                else assertTrue(text.contains("Sample loan"))
                assertEquals(if(compact)View.GONE else View.VISIBLE,view.findViewById<View>(R.id.widget_due).visibility)
                val density=context.resources.displayMetrics.density
                val w=(350*density).toInt();val h=((if(compact)190 else 250)*density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
        }
    }
}
