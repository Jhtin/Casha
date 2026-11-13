package com.example.casha

import android.graphics.*
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.ReceiptScanner
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ReceiptPhotoRegressionTest {
    private fun verify(file: File) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "receipt_regression_${UUID.randomUUID()}"
        try {
            val result = ReceiptScanner.scan(context, Uri.fromFile(file), scope)
            assertEquals(18400L, result.amount)
            assertTrue(result.note.contains("total", ignoreCase = true))
        } finally {
            val folder = File(context.filesDir, "receipts/$scope")
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }
    @Test fun sidewaysReceiptSeparatesTotalFromCashAndChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(1100, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f; typeface = Typeface.MONOSPACE }
        listOf("ORDER SLIP 0000135557", "95394954  22.00  44.00", "27642900  35.00  70.00", "76075665  35.00  70.00", "Line Items: 3 Quantity 6", "TOTAL DUE       184.00", "CASH            200.00", "CHANGE           16.00", "2026-11-02 16:15:18").forEachIndexed { i, line -> canvas.drawText(line, 40f, 100f + i * 100f, paint) }
        val sideways = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true)
        val file = File(context.cacheDir, "receipt_numbers_${UUID.randomUUID()}.jpg")
        try { file.outputStream().use { sideways.compress(Bitmap.CompressFormat.JPEG, 95, it) }; verify(file) }
        finally { file.delete(); sideways.recycle(); bitmap.recycle() }
    }
    @Test fun ownerProvidedSidewaysPhotoHas184PesoTotal() {
        val path = InstrumentationRegistry.getArguments().getString("receiptFixture")
        assumeTrue("Optional owner photo is supplied only locally", path != null)
        verify(File(requireNotNull(path)))
    }
}
