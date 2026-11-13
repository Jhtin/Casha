package com.example.casha

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.*
import com.example.casha.domain.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class FreeFeaturesDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope="test_features_${UUID.randomUUID()}"
    private val finance get()=FinanceStore(context,scope)
    @After fun cleanup() {
        GeminiKeyStore(context,scope).remove()
        context.deleteSharedPreferences("finance_$scope");context.deleteSharedPreferences("gemini_$scope")
        File(context.filesDir,"receipts/$scope").deleteRecursively()
    }
    @Test fun keyIsEncryptedAccountScopedAndRemovable() {
        val key="synthetic_test_key_0123456789abcdef_0123456789"
        val keys=GeminiKeyStore(context,scope);assertTrue(keys.save(key));assertEquals(key,keys.read())
        assertFalse(context.getSharedPreferences("gemini_$scope",0).all.values.any {it==key})
        assertNull(GeminiKeyStore(context,"other_$scope").read())
        assertTrue(keys.remove());assertNull(keys.read())
    }
    @Test fun cashLogsAreIdempotentReversibleAndScoped() {
        val original=CashEntry("cash-1",false,12345,LocalDate.now(),"Food")
        assertTrue(finance.addCash(original));assertTrue(finance.addCash(original));assertFalse(finance.addCash(original.copy(amount=12346)))
        assertEquals(1,finance.cashEntries().size);assertTrue(finance.reverseCash(original.id));assertFalse(finance.reverseCash(original.id))
        assertEquals(0L,finance.cashEntries().sumOf {if(it.income)it.amount else -it.amount})
        assertTrue(FinanceStore(context,"other_$scope").cashEntries().isEmpty())
    }
    @Test fun suggestionsDeduplicateAndHandledReceiptsDoNotReappear() {
        val suggestion=PaymentSuggestion("fingerprint",1000,LocalDate.now(),"REF12345","Receipt OCR")
        assertTrue(finance.addSuggestion(suggestion));assertTrue(finance.addSuggestion(suggestion));assertEquals(1,finance.suggestions().size)
        finance.saveProof("payment-1",suggestion.reference,null);assertTrue(finance.referenceUsed("REF12345"))
        finance.dismissSuggestion(suggestion.id);finance.addSuggestion(suggestion);assertTrue(finance.suggestions().isEmpty())
    }
    @Test fun budgetAndSafeMetadataExportRoundTripWithoutSecrets() {
        val profile=PaydayProfile(LocalDate.now(),LocalDate.now().plusDays(15),100_000,50_000,salaryAccountId="acc_bank")
        finance.saveProfile(profile);finance.savePolicy("l1",DebtPolicy(2400,2,true));finance.addCash(CashEntry("c1",true,1000,LocalDate.now(),"Extra"))
        val exported=finance.exportLocal();val metadata=finance.validatedBackupMetadata(exported)
        assertTrue(metadata.containsKey("profile"));assertTrue(metadata.containsKey("cash"));assertFalse(metadata.keys.any {it.contains("key",ignoreCase=true)})
        assertTrue(finance.restoreMetadata(metadata));assertEquals(profile,finance.profile())
    }
    @Test fun defaultSalaryWalletAndProfileSalaryWalletPersistAndRoundTrip() {
        assertEquals("acc_bank", finance.defaultSalaryAccountId())
        assertTrue(finance.setDefaultSalaryAccountId("acc_ewallet"))
        assertEquals("acc_ewallet", finance.defaultSalaryAccountId())

        val profile = PaydayProfile(LocalDate.now(), LocalDate.now().plusDays(15), 100_000, 50_000, salaryAccountId = "acc_cash")
        assertTrue(finance.saveProfile(profile))
        val loaded = finance.profile()
        assertEquals("acc_cash", loaded.salaryAccountId)

        val exported = finance.exportLocal()
        val metadata = finance.validatedBackupMetadata(exported)
        assertTrue(metadata.containsKey("default_salary_account"))
        assertTrue(metadata.containsKey("profile"))
    }
    @Test fun bundledOcrExtractsAmountDateAndReferenceWithoutCloud() {
        val folder=File(context.filesDir,"receipts/$scope").apply {mkdirs()}
        val bitmap=Bitmap.createBitmap(1500,700,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=64f}
        canvas.drawText("PAYMENT SUCCESSFUL",60f,120f,paint)
        canvas.drawText("Amount: PHP 1,234.56",60f,250f,paint)
        canvas.drawText("Reference: ABC12345",60f,380f,paint)
        canvas.drawText("2026-11-02",60f,510f,paint)
        val file=File(folder,"fixture.png");file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        val result=ReceiptScanner.scan(context,uri,scope)
        assertEquals(123456L,result.amount);assertEquals(LocalDate.of(2026,11,2),result.date);assertEquals("ABC12345",result.reference)
        assertTrue(ReceiptScanner.file(context,requireNotNull(result.receipt),scope).exists())
    }
}
