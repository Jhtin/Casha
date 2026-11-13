package com.example.casha.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.casha.domain.PaymentDetector
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object ReceiptScanner {
    fun createCaptureTarget(context:Context,scope:String):Pair<File,Uri> {
        val folder=File(context.filesDir,"receipts/$scope").apply { mkdirs() }
        val file=File(folder,"camera_${UUID.randomUUID()}.jpg")
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        return file to uri
    }

    fun scan(context:Context,uri:Uri,scope:String):PaymentSuggestion {
        val folder=File(context.filesDir,"receipts/$scope").apply { mkdirs() }
        val source=File(folder,"${UUID.randomUUID()}.tmp")
        var bitmap:Bitmap?=null
        var output:File?=null
        val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { target ->
                val buffer=ByteArray(8192);var total=0
                while(true) {val size=input.read(buffer);if(size<0)break;total+=size;require(total<=25*1024*1024) { "Choose an image smaller than 25 MB." };target.write(buffer,0,size)}
            } } ?: error("This image cannot be read.")
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(source.path,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0) { "Choose a supported receipt image." }
            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2
            val decoded=BitmapFactory.decodeFile(source.path,BitmapFactory.Options().apply { inSampleSize=sample })?:error("Cannot decode this image.")
            val rotation=runCatching {
                when(ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }.getOrDefault(0f)
            bitmap=if(rotation!=0f) {
                val rotated=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,Matrix().apply { postRotate(rotation) },true)
                if(rotated!=decoded) decoded.recycle()
                rotated
            } else decoded
            // EXIF alone is insufficient for sideways screenshots/downloaded receipt photos.
            // Rebuild visual rows because OCR blocks often separate the labels and amount column.
            var recognized = ""
            var parsed = PaymentDetector.parse("")
            var bestRotation = 0
            var bestScore = -1
            for (angle in listOf(0, 90, 270, 180)) {
                val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, angle)), 30, TimeUnit.SECONDS)
                val rows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()
                result.textBlocks.flatMap { it.lines }.sortedBy { it.boundingBox?.centerY() ?: 0 }.forEach { line ->
                    val box = line.boundingBox
                    val row = if (box == null) null else rows.lastOrNull()?.takeIf { existing ->
                        val other = existing.first().boundingBox
                        other != null && kotlin.math.abs(other.centerY() - box.centerY()) <= minOf(other.height(), box.height()) * 0.55
                    }
                    if (row == null) rows.add(mutableListOf(line)) else row.add(line)
                }
                val rowText = rows.joinToString("\n") { row -> row.sortedBy { it.boundingBox?.left ?: 0 }.joinToString(" ") { it.text } }
                val text = if (PaymentDetector.receiptTotal(rowText) != null) rowText else result.text
                val candidate = PaymentDetector.parse(text)
                val score = when { PaymentDetector.receiptTotal(text) != null -> 10000
                    candidate.amount != null -> 1000 + text.length.coerceAtMost(999)
                    else -> text.length.coerceAtMost(999) }
                if (score > bestScore) { recognized = text; parsed = candidate; bestRotation = angle; bestScore = score }
                if (score == 10000) break
            }
            if (bestRotation != 0) {
                val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                    Matrix().apply { postRotate(bestRotation.toFloat()) }, true)
                if (oriented != bitmap) bitmap.recycle()
                bitmap = oriented
            }
            val filename="${UUID.randomUUID()}.jpg";output=File(folder,filename)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,92,it)) }
            return PaymentSuggestion(FinanceStore.fingerprint("receipt|${recognized.trim()}"),parsed.amount,parsed.date,parsed.reference,"Receipt OCR","$scope/$filename",parsed.reviewNote)
        } catch(e:Exception) {output?.delete();throw e}
        finally {source.delete();bitmap?.recycle();recognizer.close()}
    }
    fun file(context:Context,relative:String,scope:String):File {
        require(relative.startsWith("$scope/"))
        val root=File(context.filesDir,"receipts").canonicalFile
        val file=File(root,relative).canonicalFile
        require(file.path.startsWith(root.path+File.separator) && file.isFile)
        return file
    }
}
